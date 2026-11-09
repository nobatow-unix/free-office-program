"""Delta updates for the Android app.

The phone already has the old APK; instead of the whole new file it downloads only the difference
(bsdiff algorithm) and rebuilds the new APK byte-for-byte, then checks its SHA-256.

Patch format "ICQDIFF1" (all integers little-endian int64):
    magic(8) | new_size | len(ctrl_z) | len(diff_z) | len(extra_z) | ctrl_z | diff_z | extra_z
    *_z = zlib-compressed bsdiff blocks; ctrl = triples (add, copy, seek) in bsdiff "offtout" encoding.
"""
import hashlib
import os
import struct
import threading
import zlib

MAGIC = b"ICQDIFF1"
_lock = threading.Lock()


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def make_patch(old: bytes, new: bytes) -> bytes:
    import bsdiff4.core  # pip install bsdiff4
    ctrl, diff, extra = bsdiff4.core.diff(old, new)
    blocks = [zlib.compress(b, 9) for b in (ctrl, diff, extra)]
    head = MAGIC + struct.pack("<qqqq", len(new), *(len(b) for b in blocks))
    return head + b"".join(blocks)


def _offtin(b: bytes, i: int) -> int:
    v = int.from_bytes(b[i:i + 8], "little")
    return -(v & ~(1 << 63)) if v & (1 << 63) else v


def apply_patch(old: bytes, patch: bytes) -> bytes:
    """Same algorithm as the phone (data/update/DeltaPatch.kt) — used by the tests."""
    assert patch[:8] == MAGIC
    new_size, lc, ld, le = struct.unpack("<qqqq", patch[8:40])
    p = 40
    ctrl = zlib.decompress(patch[p:p + lc]); p += lc
    diff = zlib.decompress(patch[p:p + ld]); p += ld
    extra = zlib.decompress(patch[p:p + le])
    out = bytearray(new_size)
    np = op = dp = ep = 0
    for c in range(0, len(ctrl), 24):
        x, y, z = _offtin(ctrl, c), _offtin(ctrl, c + 8), _offtin(ctrl, c + 16)
        for i in range(x):
            o = old[op + i] if 0 <= op + i < len(old) else 0
            out[np + i] = (diff[dp + i] + o) & 0xFF
        np += x; op += x; dp += x
        out[np:np + y] = extra[ep:ep + y]
        np += y; ep += y
        op += z
    return bytes(out)


class Deltas:
    """apk/history/<code>.apk keeps old versions; apk/patches/<from>-<to>.icqdiff are built in the background."""

    def __init__(self, apk_path: str):
        self.apk = apk_path
        base = os.path.dirname(apk_path)
        self.history = os.path.join(base, "history")
        self.patches = os.path.join(base, "patches")
        self._sha = None  # (mtime, sha)

    def current_code(self) -> int:
        import json
        try:
            with open(os.path.join(os.path.dirname(self.apk), "version.json"), encoding="utf-8") as f:
                return int(json.load(f).get("code") or 0)
        except (OSError, ValueError):
            return 0

    def sha(self) -> str:
        if not os.path.exists(self.apk):
            return ""
        m = os.path.getmtime(self.apk)
        if not self._sha or self._sha[0] != m:
            self._sha = (m, sha256_of(self.apk))
        return self._sha[1]

    def patch_path(self, old_code: int, new_code: int) -> str:
        return os.path.join(self.patches, f"{old_code}-{new_code}.icqdiff")

    def archive_current(self) -> None:
        """Keep a copy of the APK being served, so the next version can be a small patch from it."""
        code = self.current_code()
        if not code or not os.path.exists(self.apk):
            return
        os.makedirs(self.history, exist_ok=True)
        dst = os.path.join(self.history, f"{code}.apk")
        if not os.path.exists(dst) or os.path.getsize(dst) != os.path.getsize(self.apk):
            import shutil
            shutil.copyfile(self.apk, dst)

    def build_all(self, keep: int = 3) -> None:
        """Patches from the last `keep` older versions to the current one (runs in a thread)."""
        with _lock:
            try:
                self.archive_current()
                cur = self.current_code()
                if not cur or not os.path.isdir(self.history):
                    return
                olds = sorted(int(n[:-4]) for n in os.listdir(self.history) if n.endswith(".apk") and n[:-4].isdigit())
                olds = [c for c in olds if c < cur][-keep:]
                os.makedirs(self.patches, exist_ok=True)
                with open(self.apk, "rb") as f:
                    new = f.read()
                for c in olds:
                    out = self.patch_path(c, cur)
                    if os.path.exists(out):
                        continue
                    with open(os.path.join(self.history, f"{c}.apk"), "rb") as f:
                        old = f.read()
                    data = make_patch(old, new)
                    if len(data) < len(new) * 0.8:  # only worth it if clearly smaller
                        tmp = out + ".tmp"
                        with open(tmp, "wb") as f:
                            f.write(data)
                        os.replace(tmp, out)
                    print(f"[update] patch {c}->{cur}: {len(data) // 1024} KB (full {len(new) // 1024} KB)", flush=True)
                # old patches to other targets are useless now
                for n in os.listdir(self.patches):
                    if not n.endswith(f"-{cur}.icqdiff"):
                        try:
                            os.remove(os.path.join(self.patches, n))
                        except OSError:
                            pass
            except Exception as e:  # never break the server because of updates
                print(f"[update] patches failed: {e}", flush=True)

    def start(self) -> None:
        threading.Thread(target=self.build_all, daemon=True).start()
