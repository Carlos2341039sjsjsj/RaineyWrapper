package com.raineywrapper.player

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Applies IPS, BPS and PPF 3.0 patches (ROM hacks, translations, ISO mods).
 * Big files are streamed, so an ISO never has to fit in memory. Returns "OK" or an error message.
 */
object RomPatcher {
    fun apply(base: File, patch: ByteArray, out: File): String = try {
        when {
            patch.hasMagic("PATCH") -> ips(base, patch, out)
            patch.hasMagic("BPS1") -> bps(base, patch, out)
            patch.hasMagic("PPF30") -> ppf(base, patch, out)
            patch.hasMagic("PPF") -> "Solo se soportan parches PPF 3.0"
            patch.hasMagic("UPS1") -> "Los parches UPS todavia no estan soportados (usa IPS, BPS o PPF)"
            patch.size > 3 && patch[0] == 0xD6.toByte() && patch[1] == 0xC3.toByte() && patch[2] == 0xC4.toByte() ->
                "Los parches xdelta todavia no estan soportados (usa IPS, BPS o PPF)"
            else -> "Formato de parche no reconocido (soporto IPS, BPS y PPF 3.0)"
        }
    } catch (e: java.io.EOFException) {
        "El parche no corresponde a este juego (o esta danado)"
    } catch (e: IndexOutOfBoundsException) {
        "El parche esta danado"
    }

    private fun ByteArray.hasMagic(s: String) = size >= s.length && s.indices.all { this[it] == s[it].code.toByte() }

    // ---------------------------------------------------------------- IPS
    private fun ips(base: File, p: ByteArray, out: File): String {
        base.copyTo(out, overwrite = true, bufferSize = 1 shl 20)
        RandomAccessFile(out, "rw").use { raf ->
            var i = 5
            while (i + 3 <= p.size) {
                val atEof = p[i] == 'E'.code.toByte() && p[i + 1] == 'O'.code.toByte() && p[i + 2] == 'F'.code.toByte()
                if (atEof && (i + 3 == p.size || i + 6 == p.size)) {
                    if (i + 6 == p.size) raf.setLength(u24(p, i + 3).toLong())
                    return "OK"
                }
                val offset = u24(p, i).toLong()
                i += 3
                if (i + 2 > p.size) break
                val size = u16(p, i)
                i += 2
                raf.seek(offset)
                if (size == 0) {
                    if (i + 3 > p.size) break
                    val count = u16(p, i)
                    val value = p[i + 2]
                    i += 3
                    raf.write(ByteArray(count) { value })
                } else {
                    if (i + size > p.size) break
                    raf.write(p, i, size)
                    i += size
                }
            }
        }
        return "El parche IPS esta incompleto o danado"
    }

    // ---------------------------------------------------------------- BPS
    private fun bps(base: File, p: ByteArray, out: File): String {
        if (p.size < 19) return "Parche BPS danado"
        if (crc(p, 0, p.size - 4) != le32(p, p.size - 4)) return "El parche BPS esta danado (CRC)"
        var pos = 4
        fun num(): Long {
            var data = 0L
            var shift = 1L
            while (true) {
                val x = p[pos++].toInt() and 0xFF
                data += (x and 0x7F) * shift
                if ((x and 0x80) != 0) break
                shift = shift shl 7
                data += shift
            }
            return data
        }
        val sourceSize = num()
        val targetSize = num()
        val metaSize = num()
        pos += metaSize.toInt()
        if (base.length() != sourceSize) return "Este parche es para otra version del juego (tamano distinto)"
        if (crcFile(base) != le32(p, p.size - 12)) return "Este parche es para otra version del juego (CRC distinto)"
        val end = p.size - 12
        val buf = ByteArray(1 shl 20)
        var outPos = 0L
        var sourceRel = 0L
        var targetRel = 0L
        RandomAccessFile(base, "r").use { src ->
            RandomAccessFile(out, "rw").use { dst ->
                dst.setLength(0)
                while (pos < end) {
                    val cmd = num()
                    val length = (cmd shr 2) + 1
                    when ((cmd and 3L).toInt()) {
                        0 -> {
                            copyRange(src, outPos, dst, length, buf)
                            outPos += length
                        }
                        1 -> {
                            dst.write(p, pos, length.toInt())
                            pos += length.toInt()
                            outPos += length
                        }
                        2 -> {
                            val d = num()
                            sourceRel += (if ((d and 1L) != 0L) -1 else 1) * (d shr 1)
                            copyRange(src, sourceRel, dst, length, buf)
                            sourceRel += length
                            outPos += length
                        }
                        else -> {
                            val d = num()
                            targetRel += (if ((d and 1L) != 0L) -1 else 1) * (d shr 1)
                            var left = length
                            while (left > 0) {
                                val n = minOf(left, outPos - targetRel, buf.size.toLong()).toInt()
                                if (n <= 0) return "El parche BPS esta danado"
                                dst.seek(targetRel)
                                dst.readFully(buf, 0, n)
                                dst.seek(outPos)
                                dst.write(buf, 0, n)
                                targetRel += n
                                outPos += n
                                left -= n
                            }
                        }
                    }
                }
                if (outPos != targetSize) return "El parche BPS genero un tamano incorrecto"
            }
        }
        if (crcFile(out) != le32(p, p.size - 8)) return "El resultado no coincide con el esperado (CRC)"
        return "OK"
    }

    private fun copyRange(src: RandomAccessFile, from: Long, dst: RandomAccessFile, length: Long, buf: ByteArray) {
        src.seek(from)
        var left = length
        while (left > 0) {
            val n = minOf(left, buf.size.toLong()).toInt()
            src.readFully(buf, 0, n)
            dst.write(buf, 0, n)
            left -= n
        }
    }

    // ---------------------------------------------------------------- PPF 3.0
    private fun ppf(base: File, p: ByteArray, out: File): String {
        if (p.size < 60 || p[5].toInt() != 2) return "Solo se soportan parches PPF 3.0"
        val blockCheck = p[57].toInt() == 1
        val undo = p[58].toInt() == 1
        var i = 60 + if (blockCheck) 1024 else 0
        var end = p.size
        val diz = findText(p, "@BEGIN_FILE_ID.DIZ")
        if (diz > i) end = diz
        base.copyTo(out, overwrite = true, bufferSize = 1 shl 20)
        RandomAccessFile(out, "rw").use { raf ->
            while (i + 9 <= end) {
                var offset = 0L
                for (k in 7 downTo 0) offset = (offset shl 8) or (p[i + k].toLong() and 0xFF)
                val len = p[i + 8].toInt() and 0xFF
                i += 9
                if (i + len > end) return "El parche PPF esta incompleto"
                raf.seek(offset)
                raf.write(p, i, len)
                i += len
                if (undo) i += len
            }
        }
        return "OK"
    }

    /** Index of [text] in the last few KB of [b] (where a PPF keeps its optional FILE_ID.DIZ), or -1. */
    private fun findText(b: ByteArray, text: String): Int {
        val t = text.toByteArray(Charsets.US_ASCII)
        var i = maxOf(0, b.size - 4096)
        while (i + t.size <= b.size) {
            var ok = true
            for (k in t.indices) {
                if (b[i + k] != t[k]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    // ---------------------------------------------------------------- helpers
    private fun u16(p: ByteArray, i: Int) = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)

    private fun u24(p: ByteArray, i: Int) =
        ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)

    private fun le32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)

    private fun crc(b: ByteArray, off: Int, len: Int): Long {
        val c = CRC32()
        c.update(b, off, len)
        return c.value
    }

    private fun crcFile(f: File): Long {
        val c = CRC32()
        val buf = ByteArray(1 shl 20)
        f.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                c.update(buf, 0, n)
            }
        }
        return c.value
    }
}
