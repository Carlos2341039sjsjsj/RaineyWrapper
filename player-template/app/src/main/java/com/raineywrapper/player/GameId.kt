package com.raineywrapper.player

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the identifier a core uses to find a game's mods: N64 internal ROM name, PSP disc ID
 * (ISO or PBP), GameCube/Wii game ID. Returns null when it can't be read (e.g. CSO, CHD).
 */
object GameId {
    fun detect(file: File, system: String): String? = try {
        when (system) {
            "n64" -> n64(file)
            "psp" -> psp(file)
            "gamecube", "wii", "gc" -> dolphin(file)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** The 20-byte internal name at 0x20 of the header, whatever the byte order (z64, v64, n64). */
    private fun n64(file: File): String? = RandomAccessFile(file, "r").use { raf ->
        val head = ByteArray(0x40)
        raf.readFully(head)
        val raw = head.copyOfRange(0x20, 0x34)
        when (head[0].toInt() and 0xFF) {
            0x80 -> Unit
            0x37 -> for (i in raw.indices step 2) {
                val t = raw[i]
                raw[i] = raw[i + 1]
                raw[i + 1] = t
            }
            0x40 -> for (i in raw.indices step 4) {
                val a = raw[i]
                val b = raw[i + 1]
                raw[i] = raw[i + 3]
                raw[i + 1] = raw[i + 2]
                raw[i + 2] = b
                raw[i + 3] = a
            }
            else -> return@use null
        }
        String(raw, Charsets.US_ASCII).trim { it <= ' ' }.ifEmpty { null }
    }

    private fun dolphin(file: File): String? = RandomAccessFile(file, "r").use { raf ->
        val offset = when (file.extension.lowercase()) {
            "iso", "gcm" -> 0L
            "wbfs" -> 0x200L
            "rvz", "wia" -> 0x58L
            else -> return@use null
        }
        val b = ByteArray(6)
        raf.seek(offset)
        raf.readFully(b)
        val id = String(b, Charsets.US_ASCII)
        if (Regex("[A-Z0-9]{6}").matches(id)) id else null
    }

    private class Entry(val name: String, val extent: Long, val size: Long, val dir: Boolean)

    private fun psp(file: File): String? = RandomAccessFile(file, "r").use { raf ->
        val magic = ByteArray(4)
        raf.readFully(magic)
        if (magic[0].toInt() == 0 && magic[1] == 'P'.code.toByte() && magic[2] == 'B'.code.toByte() && magic[3] == 'P'.code.toByte()) {
            val h = ByteArray(16)
            raf.seek(0)
            raf.readFully(h)
            val start = le32(h, 8)
            val stop = le32(h, 12)
            if (stop <= start || stop - start > 1_000_000L) return@use null
            val sfo = ByteArray((stop - start).toInt())
            raf.seek(start)
            raf.readFully(sfo)
            return@use discId(sfo)
        }
        // ISO 9660: the root directory record sits in the primary volume descriptor (sector 16).
        val pvd = ByteArray(2048)
        raf.seek(16L * 2048)
        raf.readFully(pvd)
        if (String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return@use null
        val root = listDir(raf, le32(pvd, 158), le32(pvd, 166))
        val game = root.firstOrNull { it.dir && it.name.equals("PSP_GAME", true) } ?: return@use null
        val entry = listDir(raf, game.extent, game.size)
            .firstOrNull { !it.dir && it.name.equals("PARAM.SFO", true) } ?: return@use null
        if (entry.size > 1_000_000L) return@use null
        val sfo = ByteArray(entry.size.toInt())
        raf.seek(entry.extent * 2048)
        raf.readFully(sfo)
        discId(sfo)
    }

    private fun listDir(raf: RandomAccessFile, extent: Long, size: Long): List<Entry> {
        val data = ByteArray(minOf(size, 1L shl 20).toInt())
        raf.seek(extent * 2048)
        raf.readFully(data)
        val out = ArrayList<Entry>()
        var i = 0
        while (i + 34 <= data.size) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) {
                i = (i / 2048 + 1) * 2048
                continue
            }
            val nameLen = data[i + 32].toInt() and 0xFF
            if (i + 33 + nameLen > data.size) break
            val name = String(data, i + 33, nameLen, Charsets.US_ASCII).substringBefore(';')
            out.add(Entry(name, le32(data, i + 2), le32(data, i + 10), (data[i + 25].toInt() and 2) != 0))
            i += len
        }
        return out
    }

    /** DISC_ID from a PARAM.SFO. */
    private fun discId(sfo: ByteArray): String? {
        if (sfo.size < 20 || sfo[1] != 'P'.code.toByte() || sfo[2] != 'S'.code.toByte() || sfo[3] != 'F'.code.toByte()) return null
        val keyTable = le32(sfo, 8).toInt()
        val dataTable = le32(sfo, 12).toInt()
        val count = le32(sfo, 16).toInt()
        for (k in 0 until count) {
            val e = 20 + k * 16
            if (e + 16 > sfo.size) break
            val key = cString(sfo, keyTable + le16(sfo, e))
            if (key == "DISC_ID") {
                val id = cString(sfo, dataTable + le32(sfo, e + 12).toInt())
                return id.ifEmpty { null }
            }
        }
        return null
    }

    private fun cString(b: ByteArray, start: Int): String {
        if (start < 0 || start >= b.size) return ""
        var end = start
        while (end < b.size && b[end].toInt() != 0) end++
        return String(b, start, end - start, Charsets.US_ASCII)
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)
}
