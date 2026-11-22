package com.raineywrapper.converter

import java.io.OutputStream
import java.util.zip.Adler32
import java.util.zip.CRC32

/**
 * Aplica parches de ROM en memoria: IPS, UPS, BPS y xdelta (VCDIFF sin compresion secundaria).
 * El formato se reconoce por los primeros bytes del parche, no por la extension.
 */
object RomPatcher {
    val EXTENSIONS = listOf("ips", "ups", "bps", "xdelta", "vcdiff")

    /** Tope de tamano de la ROM: se carga entera en memoria. */
    const val MAX_BYTES = 200L * 1024 * 1024

    private const val TYPE_NOOP = 0
    private const val TYPE_ADD = 1
    private const val TYPE_RUN = 2
    private const val TYPE_COPY = 3

    // Tabla de codigos por defecto de VCDIFF (RFC 3284, seccion 5.6): dos instrucciones por opcode.
    private val type1 = IntArray(256)
    private val size1 = IntArray(256)
    private val mode1 = IntArray(256)
    private val type2 = IntArray(256)
    private val size2 = IntArray(256)
    private val mode2 = IntArray(256)

    init {
        var n = 0
        type1[n] = TYPE_RUN; n++
        for (s in 0..17) {
            type1[n] = TYPE_ADD; size1[n] = s; n++
        }
        for (m in 0..8) {
            type1[n] = TYPE_COPY; size1[n] = 0; mode1[n] = m; n++
            for (s in 4..18) {
                type1[n] = TYPE_COPY; size1[n] = s; mode1[n] = m; n++
            }
        }
        for (m in 0..5) {
            for (a in 1..4) {
                for (c in 4..6) {
                    type1[n] = TYPE_ADD; size1[n] = a
                    type2[n] = TYPE_COPY; size2[n] = c; mode2[n] = m
                    n++
                }
            }
        }
        for (m in 6..8) {
            for (a in 1..4) {
                type1[n] = TYPE_ADD; size1[n] = a
                type2[n] = TYPE_COPY; size2[n] = 4; mode2[n] = m
                n++
            }
        }
        for (m in 0..8) {
            type1[n] = TYPE_COPY; size1[n] = 4; mode1[n] = m
            type2[n] = TYPE_ADD; size2[n] = 1
            n++
        }
    }

    private class Reader(val d: ByteArray, var pos: Int) {
        fun u8(): Int {
            if (pos < 0 || pos >= d.size) throw UserError("El archivo de parche esta incompleto o danado.")
            val b = d[pos].toInt() and 0xFF
            pos++
            return b
        }

        /** Numero de UPS/BPS. */
        fun beatVar(): Long {
            var data = 0L
            var shift = 1L
            while (true) {
                val x = u8()
                data += (x and 0x7F).toLong() * shift
                if ((x and 0x80) != 0) break
                shift = shift shl 7
                data += shift
                if (shift > (1L shl 56)) throw UserError("El archivo de parche esta danado.")
            }
            return data
        }

        /** Numero de VCDIFF (7 bits por byte, el mas significativo primero). */
        fun vcdVar(): Int {
            var v = 0L
            while (true) {
                val x = u8()
                v = (v shl 7) or (x and 0x7F).toLong()
                if (v > Int.MAX_VALUE.toLong()) throw UserError("El archivo de parche esta danado.")
                if ((x and 0x80) == 0) break
            }
            return v.toInt()
        }
    }

    private fun crc(data: ByteArray): Long {
        val c = CRC32()
        c.update(data, 0, data.size)
        return c.value
    }

    private fun u32le(d: ByteArray, i: Int): Long {
        if (i < 0 || i + 4 > d.size) throw UserError("El archivo de parche esta incompleto o danado.")
        return (d[i].toLong() and 0xFF) or ((d[i + 1].toLong() and 0xFF) shl 8) or
            ((d[i + 2].toLong() and 0xFF) shl 16) or ((d[i + 3].toLong() and 0xFF) shl 24)
    }

    private fun startsWith(p: ByteArray, magic: String): Boolean {
        if (p.size < magic.length) return false
        for (i in magic.indices) if (p[i].toInt() != magic[i].code) return false
        return true
    }

    private const val WRONG_ROM = "Este parche no es para esta ROM (otra version o region). Proba con la ROM original sin modificar."

    /** Aplica el parche y escribe el resultado en [sink]. */
    fun apply(rom: ByteArray, patch: ByteArray, sink: OutputStream) {
        try {
            if (startsWith(patch, "PATCH")) sink.write(ips(rom, patch))
            else if (startsWith(patch, "UPS1")) sink.write(ups(rom, patch))
            else if (startsWith(patch, "BPS1")) sink.write(bps(rom, patch))
            else if (patch.size > 4 && (patch[0].toInt() and 0xFF) == 0xD6 && (patch[1].toInt() and 0xFF) == 0xC3 &&
                (patch[2].toInt() and 0xFF) == 0xC4
            ) vcdiff(rom, patch, sink)
            else throw UserError("No reconozco el formato del parche. Se aceptan .ips, .ups, .bps y .xdelta.")
        } catch (e: IndexOutOfBoundsException) {
            throw UserError(WRONG_ROM)
        } catch (e: NegativeArraySizeException) {
            throw UserError(WRONG_ROM)
        }
    }

    private fun ips(rom: ByteArray, p: ByteArray): ByteArray {
        // Primera pasada: tamano final.
        var size = rom.size
        var i = 5
        var truncate = -1
        while (true) {
            val off = ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)
            i += 3
            if (off == 0x454F46) {
                if (p.size - i == 3) {
                    truncate = ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)
                }
                break
            }
            var n = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            i += 2
            if (n == 0) {
                n = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                i += 3
            } else {
                i += n
            }
            if (off + n > size) size = off + n
        }
        if (size.toLong() > MAX_BYTES) throw UserError("El resultado es demasiado grande para parchearlo aqui.")
        val out = rom.copyOf(size)
        // Segunda pasada: aplicar.
        i = 5
        while (true) {
            val off = ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)
            i += 3
            if (off == 0x454F46) break
            val n = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            i += 2
            if (n == 0) {
                val rl = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                val v = p[i + 2]
                i += 3
                for (k in 0 until rl) out[off + k] = v
            } else {
                for (k in 0 until n) out[off + k] = p[i + k]
                i += n
            }
        }
        return if (truncate in 0 until out.size) out.copyOf(truncate) else out
    }

    private fun ups(rom: ByteArray, p: ByteArray): ByteArray {
        val r = Reader(p, 4)
        val inSize = r.beatVar()
        val outSize = r.beatVar()
        if (p.size < 16) throw UserError("El archivo de parche esta incompleto o danado.")
        if (inSize != rom.size.toLong() || crc(rom) != u32le(p, p.size - 12)) throw UserError(WRONG_ROM)
        if (outSize > MAX_BYTES) throw UserError("El resultado es demasiado grande para parchearlo aqui.")
        val out = ByteArray(outSize.toInt())
        System.arraycopy(rom, 0, out, 0, minOf(rom.size, out.size))
        var pos = 0L
        val end = p.size - 12
        while (r.pos < end) {
            pos += r.beatVar()
            while (true) {
                val x = r.u8()
                if (pos < out.size) out[pos.toInt()] = (out[pos.toInt()].toInt() xor x).toByte()
                pos++
                if (x == 0) break
            }
        }
        if (crc(out) != u32le(p, p.size - 8)) throw UserError("El parche se aplico mal (la suma de control no coincide).")
        return out
    }

    private fun bps(rom: ByteArray, p: ByteArray): ByteArray {
        val r = Reader(p, 4)
        val srcSize = r.beatVar()
        val tgtSize = r.beatVar()
        val metaSize = r.beatVar()
        if (p.size < 16) throw UserError("El archivo de parche esta incompleto o danado.")
        if (srcSize != rom.size.toLong() || crc(rom) != u32le(p, p.size - 12)) throw UserError(WRONG_ROM)
        if (tgtSize > MAX_BYTES) throw UserError("El resultado es demasiado grande para parchearlo aqui.")
        r.pos += metaSize.toInt()
        val out = ByteArray(tgtSize.toInt())
        var o = 0
        var srcRel = 0L
        var tgtRel = 0L
        val end = p.size - 12
        while (r.pos < end) {
            val data = r.beatVar()
            val cmd = (data and 3L).toInt()
            val len = (data shr 2).toInt() + 1
            if (cmd == 0) {
                for (k in 0 until len) {
                    out[o] = rom[o]
                    o++
                }
            } else if (cmd == 1) {
                for (k in 0 until len) {
                    out[o] = r.u8().toByte()
                    o++
                }
            } else if (cmd == 2) {
                val d = r.beatVar()
                srcRel += if ((d and 1L) != 0L) -(d shr 1) else (d shr 1)
                for (k in 0 until len) {
                    out[o] = rom[srcRel.toInt()]
                    o++
                    srcRel++
                }
            } else {
                val d = r.beatVar()
                tgtRel += if ((d and 1L) != 0L) -(d shr 1) else (d shr 1)
                for (k in 0 until len) {
                    out[o] = out[tgtRel.toInt()]
                    o++
                    tgtRel++
                }
            }
        }
        if (crc(out) != u32le(p, p.size - 8)) throw UserError("El parche se aplico mal (la suma de control no coincide).")
        return out
    }

    private const val SECONDARY = "Este parche xdelta usa compresion secundaria y no se puede leer aqui. Aplicalo con hack64.net en el navegador o con xdelta en una PC, y despues elegi la ROM ya parcheada."

    private fun vcdiff(rom: ByteArray, p: ByteArray, sink: OutputStream) {
        val r = Reader(p, 4)
        val hdr = r.u8()
        if ((hdr and 1) != 0) throw UserError(SECONDARY)
        if ((hdr and 2) != 0) throw UserError("Este parche xdelta usa una tabla de codigos propia que no se puede leer aqui.")
        if ((hdr and 4) != 0) r.pos += r.vcdVar()
        while (r.pos < p.size) {
            val win = r.u8()
            var srcLen = 0
            var srcPos = 0
            if ((win and 3) != 0) {
                if ((win and 2) != 0) throw UserError("Este parche xdelta usa un formato que no se puede leer aqui.")
                srcLen = r.vcdVar()
                srcPos = r.vcdVar()
                if (srcLen.toLong() + srcPos.toLong() > rom.size.toLong()) throw UserError(WRONG_ROM)
            }
            val deltaLen = r.vcdVar()
            val deltaEnd = r.pos + deltaLen
            val tgtLen = r.vcdVar()
            val ind = r.u8()
            if (ind != 0) throw UserError(SECONDARY)
            val dataLen = r.vcdVar()
            val instLen = r.vcdVar()
            val addrLen = r.vcdVar()
            var adler = -1L
            if ((win and 4) != 0) {
                adler = ((r.u8().toLong() shl 24) or (r.u8().toLong() shl 16) or (r.u8().toLong() shl 8) or r.u8().toLong())
            }
            val data = Reader(p, r.pos)
            val inst = Reader(p, r.pos + dataLen)
            val addr = Reader(p, r.pos + dataLen + instLen)
            val instEnd = r.pos + dataLen + instLen
            if (addrLen < 0 || instEnd + addrLen > p.size) throw UserError("El archivo de parche esta incompleto o danado.")
            r.pos = deltaEnd
            val tgt = ByteArray(tgtLen)
            var t = 0
            val near = IntArray(4)
            var nextSlot = 0
            val same = IntArray(768)
            while (inst.pos < instEnd) {
                val idx = inst.u8()
                for (half in 0..1) {
                    val type = if (half == 0) type1[idx] else type2[idx]
                    var size = if (half == 0) size1[idx] else size2[idx]
                    val mode = if (half == 0) mode1[idx] else mode2[idx]
                    if (type != TYPE_NOOP) {
                        if (size == 0) size = inst.vcdVar()
                        if (type == TYPE_ADD) {
                            for (k in 0 until size) {
                                tgt[t] = data.u8().toByte()
                                t++
                            }
                        } else if (type == TYPE_RUN) {
                            val b = data.u8().toByte()
                            for (k in 0 until size) {
                                tgt[t] = b
                                t++
                            }
                        } else {
                            val here = srcLen + t
                            var a: Int
                            if (mode == 0) a = addr.vcdVar()
                            else if (mode == 1) a = here - addr.vcdVar()
                            else if (mode < 6) a = near[mode - 2] + addr.vcdVar()
                            else a = same[(mode - 6) * 256 + addr.u8()]
                            if (a < 0) throw UserError("El archivo de parche esta danado.")
                            near[nextSlot] = a
                            nextSlot = (nextSlot + 1) and 3
                            same[a % 768] = a
                            for (k in 0 until size) {
                                tgt[t] = if (a < srcLen) rom[srcPos + a] else tgt[a - srcLen]
                                t++
                                a++
                            }
                        }
                    }
                }
            }
            if (t != tgtLen) throw UserError("El archivo de parche esta danado.")
            if (adler >= 0) {
                val ad = Adler32()
                ad.update(tgt, 0, tgt.size)
                if (ad.value != adler) {
                    throw UserError("El parche no se pudo aplicar bien a esta ROM (suma de control distinta). Proba con la ROM original sin modificar o parchea con hack64.net.")
                }
            }
            sink.write(tgt, 0, t)
        }
    }
}
