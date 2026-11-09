package com.icqnew.app.data.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Applies an "ICQDIFF1" patch (server/apkdelta.py): rebuilds the new APK from the installed one
 * plus the small difference. bsdiff algorithm, zlib-compressed blocks, little-endian int64 header.
 */
object DeltaPatch {
    private val MAGIC = "ICQDIFF1".toByteArray()

    fun apply(oldApk: File, patch: File, out: File) {
        val p = patch.readBytes()
        require(p.size > 40 && p.copyOfRange(0, 8).contentEquals(MAGIC)) { "bad patch" }
        val newSize = le64(p, 8)
        val lc = le64(p, 16).toInt()
        val ld = le64(p, 24).toInt()
        val le = le64(p, 32).toInt()
        var pos = 40
        val ctrl = inflate(p, pos, lc); pos += lc
        val diff = inflate(p, pos, ld); pos += ld
        val extra = inflate(p, pos, le)
        require(newSize in 1..Int.MAX_VALUE) { "bad size" }
        val old = oldApk.readBytes()
        val dst = ByteArray(newSize.toInt())
        var np = 0; var op = 0L; var dp = 0; var ep = 0
        var c = 0
        while (c + 24 <= ctrl.size) {
            val x = offtin(ctrl, c).toInt()
            val y = offtin(ctrl, c + 8).toInt()
            val z = offtin(ctrl, c + 16)
            c += 24
            require(np + x <= dst.size && dp + x <= diff.size) { "corrupt patch" }
            for (i in 0 until x) {
                val oi = op + i
                val o = if (oi >= 0 && oi < old.size) old[oi.toInt()].toInt() else 0
                dst[np + i] = (diff[dp + i] + o).toByte()
            }
            np += x; op += x; dp += x
            require(np + y <= dst.size && ep + y <= extra.size) { "corrupt patch" }
            System.arraycopy(extra, ep, dst, np, y)
            np += y; ep += y
            op += z
        }
        out.writeBytes(dst)
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = r.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun le64(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    /** bsdiff "offtout": magnitude in 63 bits, sign in the top bit. */
    private fun offtin(b: ByteArray, i: Int): Long {
        val v = le64(b, i)
        return if (v < 0) -(v and Long.MAX_VALUE) else v
    }

    private fun inflate(b: ByteArray, off: Int, len: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(b, off, len)
        val out = ByteArrayOutputStream(len * 4)
        val buf = ByteArray(1 shl 16)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }
}
