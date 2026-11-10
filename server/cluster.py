"""
ICQ New cluster: several servers behind one domain, any number, added at any time.

Every server runs the same server.py and uses the same PostgreSQL (db.py) and the same Redis:
  * Redis pub/sub "icq:bus"  - a message for a user connected to another server reaches it;
  * presence                 - who is online is known on every server (each server announces its own);
  * leader                   - jobs that must run once (clean-up, auto-delete) run on one server only;
  * pending calls            - a call to someone who is offline waits in Redis, not in one server's memory;
  * files                    - an uploaded file / avatar is copied to the other servers; a server that
                               still misses a file takes it from another one when somebody asks for it.

Without ICQ_REDIS_URL nothing of this is used (one server, as before).
Environment of every server:
  ICQ_DB_URL=postgresql://icq:PASS@MAIN_IP:5432/icq
  ICQ_REDIS_URL=redis://:PASS@MAIN_IP:6379/0
  ICQ_NODE_ID=srv2                      (unique name)
  ICQ_NODE_URL=http://THIS_IP:8000      (the other servers reach this one here)
  ICQ_CLUSTER_SECRET=...                (the same on all servers)
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import socket
import time
from typing import Awaitable, Callable, Dict, List, Optional, Set, Tuple

REDIS_URL = os.environ.get("ICQ_REDIS_URL", "").strip()
ENABLED = bool(REDIS_URL)
NODE_ID = (os.environ.get("ICQ_NODE_ID") or socket.gethostname() or "node").strip()[:40]
NODE_URL = os.environ.get("ICQ_NODE_URL", "").rstrip("/")
SECRET = os.environ.get("ICQ_CLUSTER_SECRET", "")
BUS = "icq:bus"
ALIVE_TTL = 20
NAME_RE = re.compile(r"^[A-Za-z0-9_.\-]{1,80}$")


def _now() -> int:
    return int(time.time() * 1000)


# ------------------------------------------------------------------ VPS state (CPU, RAM, disk, network)
_cpu_prev: Optional[Tuple[int, int]] = None
_net_prev: Optional[Tuple[float, int, int]] = None


def _read(path: str) -> str:
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return ""


def system_stats() -> dict:
    """Linux /proc: how loaded this server is (shown to the super admin)."""
    global _cpu_prev, _net_prev
    out: dict = {"host": socket.gethostname(), "ts": _now()}
    # CPU %: difference since the previous call
    line = _read("/proc/stat").split("\n", 1)[0].split()
    if len(line) > 4:
        vals = [int(x) for x in line[1:]]
        idle = vals[3] + (vals[4] if len(vals) > 4 else 0)
        total = sum(vals)
        if _cpu_prev and total > _cpu_prev[1]:
            out["cpu"] = round(100 * (1 - (idle - _cpu_prev[0]) / (total - _cpu_prev[1])), 1)
        _cpu_prev = (idle, total)
    out["cores"] = os.cpu_count() or 1
    try:
        out["load"] = [round(x, 2) for x in os.getloadavg()]
    except OSError:
        pass
    mem = {}
    for l in _read("/proc/meminfo").splitlines():
        k, _, v = l.partition(":")
        if v.strip():
            mem[k] = int(v.split()[0]) * 1024
    if mem:
        out["mem_total"] = mem.get("MemTotal", 0)
        out["mem_used"] = mem.get("MemTotal", 0) - mem.get("MemAvailable", mem.get("MemFree", 0))
        out["swap_total"] = mem.get("SwapTotal", 0)
        out["swap_used"] = mem.get("SwapTotal", 0) - mem.get("SwapFree", 0)
    try:
        import shutil
        d = shutil.disk_usage("/")
        out["disk_total"], out["disk_used"] = d.total, d.used
    except OSError:
        pass
    up = _read("/proc/uptime").split()
    if up:
        out["uptime"] = int(float(up[0]))
    # network: bytes per second since the previous call (all interfaces except lo)
    rx = tx = 0
    for l in _read("/proc/net/dev").splitlines()[2:]:
        name, _, rest = l.partition(":")
        if name.strip() == "lo" or not rest:
            continue
        f = rest.split()
        rx += int(f[0])
        tx += int(f[8])
    now = time.time()
    if _net_prev and now > _net_prev[0]:
        dt = now - _net_prev[0]
        out["net_rx"] = int(max(0, rx - _net_prev[1]) / dt)
        out["net_tx"] = int(max(0, tx - _net_prev[2]) / dt)
    _net_prev = (now, rx, tx)
    out["net_rx_total"], out["net_tx_total"] = rx, tx
    # this server program
    rss = 0
    for l in _read("/proc/self/status").splitlines():
        if l.startswith("VmRSS:"):
            rss = int(l.split()[1]) * 1024
    out["app_mem"] = rss
    return out


class Cluster:
    def __init__(self) -> None:
        self.r = None            # asyncio redis
        self.rs = None           # sync redis (for sync endpoints)
        self.leader = not ENABLED
        self.alive: Set[str] = {NODE_ID}
        self.nodes: Dict[str, str] = {}
        # uin -> {node: (active, total)} of the OTHER servers
        self.remote: Dict[int, Dict[str, Tuple[int, int]]] = {}
        self.on_send: Optional[Callable[[List[int], dict], Awaitable[None]]] = None
        self.on_kick: Optional[Callable[[int], Awaitable[None]]] = None
        self.on_kick_tokens: Optional[Callable[[int, List[str]], Awaitable[None]]] = None
        self.on_node_lost: Optional[Callable[[List[int]], Awaitable[None]]] = None
        self._tasks: List[asyncio.Task] = []
        self.extra_stats: Callable[[], dict] = lambda: {}

    # ------------------------------------------------------------ start / stop
    async def start(self) -> None:
        if not ENABLED:
            return
        import redis
        import redis.asyncio as aioredis
        self.r = aioredis.from_url(REDIS_URL, decode_responses=True, health_check_interval=30)
        self.rs = redis.from_url(REDIS_URL, decode_responses=True, socket_timeout=3)
        await self.r.hset("icq:nodes", NODE_ID, NODE_URL)
        await self.r.set(f"icq:alive:{NODE_ID}", str(_now()), ex=ALIVE_TTL)
        await self._load_presence()
        self._tasks += [
            asyncio.create_task(self._listen()),
            asyncio.create_task(self._heartbeat()),
        ]
        print(f"[cluster] node {NODE_ID} joined ({NODE_URL or 'no url'})", flush=True)

    async def stop(self) -> None:
        if not ENABLED or self.r is None:
            return
        try:
            # my users are not online through me any more
            async for key in self.r.scan_iter("icq:p:*", count=500):
                await self.r.hdel(key, NODE_ID)
            await self.r.delete(f"icq:alive:{NODE_ID}")
            if await self.r.get("icq:leader") == NODE_ID:
                await self.r.delete("icq:leader")
            await self.r.publish(BUS, json.dumps({"n": NODE_ID, "t": "bye"}))
        except Exception:  # noqa: BLE001
            pass

    # ------------------------------------------------------------ bus
    def publish(self, msg: dict) -> None:
        if not ENABLED or self.r is None:
            return
        msg["n"] = NODE_ID
        data = json.dumps(msg, ensure_ascii=False)

        async def go() -> None:
            try:
                await self.r.publish(BUS, data)
            except Exception as e:  # noqa: BLE001
                print("[cluster] publish:", e, flush=True)

        asyncio.get_event_loop().create_task(go())

    async def _listen(self) -> None:
        while True:
            try:
                ps = self.r.pubsub(ignore_subscribe_messages=True)
                await ps.subscribe(BUS)
                async for m in ps.listen():
                    try:
                        msg = json.loads(m["data"])
                    except (ValueError, TypeError):
                        continue
                    if msg.get("n") == NODE_ID:
                        continue
                    await self._on_bus(msg)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001
                print("[cluster] bus lost, reconnecting:", e, flush=True)
                await asyncio.sleep(2)

    async def _on_bus(self, msg: dict) -> None:
        t = msg.get("t")
        node = msg.get("n")
        if t == "send" and self.on_send:
            await self.on_send([int(u) for u in msg.get("u", [])], msg.get("p") or {})
        elif t == "pres":
            self._set_remote(int(msg["u"]), node, int(msg.get("a", 0)), int(msg.get("c", 0)))
        elif t == "kick" and self.on_kick:
            await self.on_kick(int(msg["u"]))
        elif t == "kicktok" and self.on_kick_tokens:
            await self.on_kick_tokens(int(msg["u"]), list(msg.get("tokens") or []))
        elif t == "bye":
            await self._drop_node(node)

    # ------------------------------------------------------------ presence
    def _set_remote(self, uin: int, node: str, active: int, total: int) -> None:
        d = self.remote.setdefault(uin, {})
        if total <= 0:
            d.pop(node, None)
            if not d:
                self.remote.pop(uin, None)
        else:
            d[node] = (active, total)

    def presence(self, uin: int, active: int, total: int) -> None:
        """This server's connections of [uin] changed."""
        if not ENABLED or self.r is None:
            return
        self.publish({"t": "pres", "u": uin, "a": active, "c": total})

        async def store() -> None:
            try:
                if total > 0:
                    await self.r.hset(f"icq:p:{uin}", NODE_ID, f"{active},{total}")
                else:
                    await self.r.hdel(f"icq:p:{uin}", NODE_ID)
            except Exception:  # noqa: BLE001
                pass

        asyncio.get_event_loop().create_task(store())

    def remote_counts(self, uin: int) -> Tuple[int, int]:
        d = self.remote.get(uin)
        if not d:
            return 0, 0
        a = t = 0
        for node, (x, y) in d.items():
            if node in self.alive:
                a += x
                t += y
        return a, t

    def remote_uins(self) -> Set[int]:
        return {u for u, d in self.remote.items() if any(n in self.alive for n in d)}

    async def _load_presence(self) -> None:
        async for key in self.r.scan_iter("icq:p:*", count=500):
            try:
                uin = int(key.split(":")[2])
            except (IndexError, ValueError):
                continue
            for node, v in (await self.r.hgetall(key)).items():
                if node == NODE_ID:
                    await self.r.hdel(key, node)  # left over from my previous run
                    continue
                a, _, t = v.partition(",")
                self._set_remote(uin, node, int(a or 0), int(t or 0))

    async def _drop_node(self, node: str) -> None:
        self.alive.discard(node)
        gone = [u for u, d in self.remote.items() if node in d]
        for u in gone:
            self._set_remote(u, node, 0, 0)
        if gone and self.on_node_lost:
            await self.on_node_lost(gone)

    async def _heartbeat(self) -> None:
        while True:
            try:
                await self.r.set(f"icq:alive:{NODE_ID}", str(_now()), ex=ALIVE_TTL)
                await self.r.hset("icq:nodes", NODE_ID, NODE_URL)
                # leader: whoever holds the key (renewed while alive)
                got = await self.r.set("icq:leader", NODE_ID, nx=True, ex=ALIVE_TTL)
                cur = await self.r.get("icq:leader")
                if cur == NODE_ID and not got:
                    await self.r.expire("icq:leader", ALIVE_TTL)
                if (cur == NODE_ID) != self.leader:
                    self.leader = cur == NODE_ID
                    print(f"[cluster] {NODE_ID} leader={self.leader}", flush=True)
                self.nodes = await self.r.hgetall("icq:nodes")
                st = system_stats()
                st.update(self.extra_stats())
                await self.r.hset("icq:stats", NODE_ID, json.dumps(st))
                alive = {NODE_ID}
                for n in self.nodes:
                    if n != NODE_ID and await self.r.exists(f"icq:alive:{n}"):
                        alive.add(n)
                for n in self.alive - alive:
                    await self._drop_node(n)
                self.alive = alive
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001
                print("[cluster] heartbeat:", e, flush=True)
            await asyncio.sleep(5)

    # ------------------------------------------------------------ sessions shown as online in «Устройства»
    def token_on(self, token: str) -> None:
        if ENABLED and self.rs is not None:
            try:
                self.rs.hset("icq:tok", token, NODE_ID)
            except Exception:  # noqa: BLE001
                pass

    def token_off(self, token: str) -> None:
        if ENABLED and self.rs is not None:
            try:
                self.rs.hdel("icq:tok", token)
            except Exception:  # noqa: BLE001
                pass

    def token_online(self, token: str) -> bool:
        if not ENABLED or self.rs is None:
            return False
        try:
            node = self.rs.hget("icq:tok", token)
        except Exception:  # noqa: BLE001
            return False
        return bool(node) and node != NODE_ID and node in self.alive

    # ------------------------------------------------------------ calls waiting for someone offline
    async def push_pending(self, uin: int, item: list, ttl_s: int) -> None:
        key = f"icq:pc:{uin}"
        await self.r.rpush(key, json.dumps(item, ensure_ascii=False))
        await self.r.expire(key, ttl_s)

    async def take_pending(self, uin: int) -> list:
        key = f"icq:pc:{uin}"
        items = await self.r.lrange(key, 0, -1)
        await self.r.delete(key)
        return [json.loads(x) for x in items]

    async def drop_pending(self, uin: int, call_id) -> None:
        key = f"icq:pc:{uin}"
        items = await self.r.lrange(key, 0, -1)
        keep = [x for x in items if (json.loads(x)[1].get("data") or {}).get("call_id") != call_id]
        await self.r.delete(key)
        if keep:
            await self.r.rpush(key, *keep)
            await self.r.expire(key, 60)

    # ------------------------------------------------------------ files on every server
    def peers(self) -> List[str]:
        return [u for n, u in self.nodes.items() if n != NODE_ID and n in self.alive and u]

    def push_file(self, kind: str, name: str, path: str) -> None:
        """Copies a new file to the other servers (in the background)."""
        if not ENABLED or not self.peers():
            return
        peers = self.peers()

        def run() -> None:
            import urllib.request
            for url in peers:
                try:
                    with open(path, "rb") as f:
                        req = urllib.request.Request(
                            f"{url}/internal/blob?kind={kind}&name={name}", data=f, method="PUT",
                            headers={"X-ICQ-Cluster": SECRET, "Content-Length": str(os.path.getsize(path)),
                                     "Content-Type": "application/octet-stream"},
                        )
                        urllib.request.urlopen(req, timeout=600).read()
                except Exception as e:  # noqa: BLE001
                    print(f"[cluster] copy {kind}/{name} -> {url}: {e}", flush=True)

        asyncio.get_event_loop().run_in_executor(None, run)

    def delete_file(self, kind: str, name: str) -> None:
        if not ENABLED:
            return
        peers = self.peers()

        def run() -> None:
            import urllib.request
            for url in peers:
                try:
                    req = urllib.request.Request(f"{url}/internal/blob?kind={kind}&name={name}", method="DELETE",
                                                 headers={"X-ICQ-Cluster": SECRET})
                    urllib.request.urlopen(req, timeout=20).read()
                except Exception:  # noqa: BLE001
                    pass

        asyncio.get_event_loop().run_in_executor(None, run)

    def fetch_file(self, kind: str, name: str, dest: str) -> bool:
        """This server has no copy yet: take it from another server (blocking, call from a thread)."""
        if not ENABLED or not NAME_RE.match(name):
            return False
        import urllib.request
        for url in self.peers():
            tmp = dest + ".part"
            try:
                req = urllib.request.Request(f"{url}/internal/blob?kind={kind}&name={name}",
                                             headers={"X-ICQ-Cluster": SECRET})
                with urllib.request.urlopen(req, timeout=600) as resp, open(tmp, "wb") as out:
                    while True:
                        chunk = resp.read(1 << 20)
                        if not chunk:
                            break
                        out.write(chunk)
                os.replace(tmp, dest)
                return True
            except Exception:  # noqa: BLE001
                try:
                    os.remove(tmp)
                except OSError:
                    pass
        return False

    async def all_stats(self) -> Dict[str, dict]:
        if not ENABLED or self.r is None:
            return {}
        out = {}
        for n, v in (await self.r.hgetall("icq:stats")).items():
            try:
                out[n] = json.loads(v)
            except ValueError:
                pass
        return out

    def info(self) -> dict:
        return {"enabled": ENABLED, "node": NODE_ID, "leader": self.leader, "nodes": self.nodes,
                "alive": sorted(self.alive)}


CLUSTER = Cluster()
