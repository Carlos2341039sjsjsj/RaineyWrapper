import pathlib

CONV = pathlib.Path("converter/app/src/main/java/com/raineywrapper/converter")

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

PATCHER = 'package com.raineywrapper.converter\n\nimport java.io.OutputStream\nimport java.util.zip.Adler32\nimport java.util.zip.CRC32\n\n/**\n * Aplica parches de ROM en memoria: IPS, UPS, BPS y xdelta (VCDIFF sin compresion secundaria).\n * El formato se reconoce por los primeros bytes del parche, no por la extension.\n */\nobject RomPatcher {\n    val EXTENSIONS = listOf("ips", "ups", "bps", "xdelta", "vcdiff")\n\n    /** Tope de tamano de la ROM: se carga entera en memoria. */\n    const val MAX_BYTES = 200L * 1024 * 1024\n\n    private const val TYPE_NOOP = 0\n    private const val TYPE_ADD = 1\n    private const val TYPE_RUN = 2\n    private const val TYPE_COPY = 3\n\n    // Tabla de codigos por defecto de VCDIFF (RFC 3284, seccion 5.6): dos instrucciones por opcode.\n    private val type1 = IntArray(256)\n    private val size1 = IntArray(256)\n    private val mode1 = IntArray(256)\n    private val type2 = IntArray(256)\n    private val size2 = IntArray(256)\n    private val mode2 = IntArray(256)\n\n    init {\n        var n = 0\n        type1[n] = TYPE_RUN; n++\n        for (s in 0..17) {\n            type1[n] = TYPE_ADD; size1[n] = s; n++\n        }\n        for (m in 0..8) {\n            type1[n] = TYPE_COPY; size1[n] = 0; mode1[n] = m; n++\n            for (s in 4..18) {\n                type1[n] = TYPE_COPY; size1[n] = s; mode1[n] = m; n++\n            }\n        }\n        for (m in 0..5) {\n            for (a in 1..4) {\n                for (c in 4..6) {\n                    type1[n] = TYPE_ADD; size1[n] = a\n                    type2[n] = TYPE_COPY; size2[n] = c; mode2[n] = m\n                    n++\n                }\n            }\n        }\n        for (m in 6..8) {\n            for (a in 1..4) {\n                type1[n] = TYPE_ADD; size1[n] = a\n                type2[n] = TYPE_COPY; size2[n] = 4; mode2[n] = m\n                n++\n            }\n        }\n        for (m in 0..8) {\n            type1[n] = TYPE_COPY; size1[n] = 4; mode1[n] = m\n            type2[n] = TYPE_ADD; size2[n] = 1\n            n++\n        }\n    }\n\n    private class Reader(val d: ByteArray, var pos: Int) {\n        fun u8(): Int {\n            if (pos < 0 || pos >= d.size) throw UserError("El archivo de parche esta incompleto o danado.")\n            val b = d[pos].toInt() and 0xFF\n            pos++\n            return b\n        }\n\n        /** Numero de UPS/BPS. */\n        fun beatVar(): Long {\n            var data = 0L\n            var shift = 1L\n            while (true) {\n                val x = u8()\n                data += (x and 0x7F).toLong() * shift\n                if ((x and 0x80) != 0) break\n                shift = shift shl 7\n                data += shift\n                if (shift > (1L shl 56)) throw UserError("El archivo de parche esta danado.")\n            }\n            return data\n        }\n\n        /** Numero de VCDIFF (7 bits por byte, el mas significativo primero). */\n        fun vcdVar(): Int {\n            var v = 0L\n            while (true) {\n                val x = u8()\n                v = (v shl 7) or (x and 0x7F).toLong()\n                if (v > Int.MAX_VALUE.toLong()) throw UserError("El archivo de parche esta danado.")\n                if ((x and 0x80) == 0) break\n            }\n            return v.toInt()\n        }\n    }\n\n    private fun crc(data: ByteArray): Long {\n        val c = CRC32()\n        c.update(data, 0, data.size)\n        return c.value\n    }\n\n    private fun u32le(d: ByteArray, i: Int): Long {\n        if (i < 0 || i + 4 > d.size) throw UserError("El archivo de parche esta incompleto o danado.")\n        return (d[i].toLong() and 0xFF) or ((d[i + 1].toLong() and 0xFF) shl 8) or\n            ((d[i + 2].toLong() and 0xFF) shl 16) or ((d[i + 3].toLong() and 0xFF) shl 24)\n    }\n\n    private fun startsWith(p: ByteArray, magic: String): Boolean {\n        if (p.size < magic.length) return false\n        for (i in magic.indices) if (p[i].toInt() != magic[i].code) return false\n        return true\n    }\n\n    private const val WRONG_ROM = "Este parche no es para esta ROM (otra version o region). Proba con la ROM original sin modificar."\n\n    /** Aplica el parche y escribe el resultado en [sink]. */\n    fun apply(rom: ByteArray, patch: ByteArray, sink: OutputStream) {\n        try {\n            if (startsWith(patch, "PATCH")) sink.write(ips(rom, patch))\n            else if (startsWith(patch, "UPS1")) sink.write(ups(rom, patch))\n            else if (startsWith(patch, "BPS1")) sink.write(bps(rom, patch))\n            else if (patch.size > 4 && (patch[0].toInt() and 0xFF) == 0xD6 && (patch[1].toInt() and 0xFF) == 0xC3 &&\n                (patch[2].toInt() and 0xFF) == 0xC4\n            ) vcdiff(rom, patch, sink)\n            else throw UserError("No reconozco el formato del parche. Se aceptan .ips, .ups, .bps y .xdelta.")\n        } catch (e: IndexOutOfBoundsException) {\n            throw UserError(WRONG_ROM)\n        } catch (e: NegativeArraySizeException) {\n            throw UserError(WRONG_ROM)\n        }\n    }\n\n    private fun ips(rom: ByteArray, p: ByteArray): ByteArray {\n        // Primera pasada: tamano final.\n        var size = rom.size\n        var i = 5\n        var truncate = -1\n        while (true) {\n            val off = ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)\n            i += 3\n            if (off == 0x454F46) {\n                if (p.size - i == 3) {\n                    truncate = ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)\n                }\n                break\n            }\n            var n = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)\n            i += 2\n            if (n == 0) {\n                n = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)\n                i += 3\n            } else {\n                i += n\n            }\n            if (off + n > size) size = off + n\n        }\n        if (size.toLong() > MAX_BYTES) throw UserError("El resultado es demasiado grande para parchearlo aqui.")\n        val out = rom.copyOf(size)\n        // Segunda pasada: aplicar.\n        i = 5\n        while (true) {\n            val off = ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)\n            i += 3\n            if (off == 0x454F46) break\n            val n = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)\n            i += 2\n            if (n == 0) {\n                val rl = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)\n                val v = p[i + 2]\n                i += 3\n                for (k in 0 until rl) out[off + k] = v\n            } else {\n                for (k in 0 until n) out[off + k] = p[i + k]\n                i += n\n            }\n        }\n        return if (truncate in 0 until out.size) out.copyOf(truncate) else out\n    }\n\n    private fun ups(rom: ByteArray, p: ByteArray): ByteArray {\n        val r = Reader(p, 4)\n        val inSize = r.beatVar()\n        val outSize = r.beatVar()\n        if (p.size < 16) throw UserError("El archivo de parche esta incompleto o danado.")\n        if (inSize != rom.size.toLong() || crc(rom) != u32le(p, p.size - 12)) throw UserError(WRONG_ROM)\n        if (outSize > MAX_BYTES) throw UserError("El resultado es demasiado grande para parchearlo aqui.")\n        val out = ByteArray(outSize.toInt())\n        System.arraycopy(rom, 0, out, 0, minOf(rom.size, out.size))\n        var pos = 0L\n        val end = p.size - 12\n        while (r.pos < end) {\n            pos += r.beatVar()\n            while (true) {\n                val x = r.u8()\n                if (pos < out.size) out[pos.toInt()] = (out[pos.toInt()].toInt() xor x).toByte()\n                pos++\n                if (x == 0) break\n            }\n        }\n        if (crc(out) != u32le(p, p.size - 8)) throw UserError("El parche se aplico mal (la suma de control no coincide).")\n        return out\n    }\n\n    private fun bps(rom: ByteArray, p: ByteArray): ByteArray {\n        val r = Reader(p, 4)\n        val srcSize = r.beatVar()\n        val tgtSize = r.beatVar()\n        val metaSize = r.beatVar()\n        if (p.size < 16) throw UserError("El archivo de parche esta incompleto o danado.")\n        if (srcSize != rom.size.toLong() || crc(rom) != u32le(p, p.size - 12)) throw UserError(WRONG_ROM)\n        if (tgtSize > MAX_BYTES) throw UserError("El resultado es demasiado grande para parchearlo aqui.")\n        r.pos += metaSize.toInt()\n        val out = ByteArray(tgtSize.toInt())\n        var o = 0\n        var srcRel = 0L\n        var tgtRel = 0L\n        val end = p.size - 12\n        while (r.pos < end) {\n            val data = r.beatVar()\n            val cmd = (data and 3L).toInt()\n            val len = (data shr 2).toInt() + 1\n            if (cmd == 0) {\n                for (k in 0 until len) {\n                    out[o] = rom[o]\n                    o++\n                }\n            } else if (cmd == 1) {\n                for (k in 0 until len) {\n                    out[o] = r.u8().toByte()\n                    o++\n                }\n            } else if (cmd == 2) {\n                val d = r.beatVar()\n                srcRel += if ((d and 1L) != 0L) -(d shr 1) else (d shr 1)\n                for (k in 0 until len) {\n                    out[o] = rom[srcRel.toInt()]\n                    o++\n                    srcRel++\n                }\n            } else {\n                val d = r.beatVar()\n                tgtRel += if ((d and 1L) != 0L) -(d shr 1) else (d shr 1)\n                for (k in 0 until len) {\n                    out[o] = out[tgtRel.toInt()]\n                    o++\n                    tgtRel++\n                }\n            }\n        }\n        if (crc(out) != u32le(p, p.size - 8)) throw UserError("El parche se aplico mal (la suma de control no coincide).")\n        return out\n    }\n\n    private const val SECONDARY = "Este parche xdelta usa compresion secundaria y no se puede leer aqui. Aplicalo con hack64.net en el navegador o con xdelta en una PC, y despues elegi la ROM ya parcheada."\n\n    private fun vcdiff(rom: ByteArray, p: ByteArray, sink: OutputStream) {\n        val r = Reader(p, 4)\n        val hdr = r.u8()\n        if ((hdr and 1) != 0) throw UserError(SECONDARY)\n        if ((hdr and 2) != 0) throw UserError("Este parche xdelta usa una tabla de codigos propia que no se puede leer aqui.")\n        if ((hdr and 4) != 0) r.pos += r.vcdVar()\n        while (r.pos < p.size) {\n            val win = r.u8()\n            var srcLen = 0\n            var srcPos = 0\n            if ((win and 3) != 0) {\n                if ((win and 2) != 0) throw UserError("Este parche xdelta usa un formato que no se puede leer aqui.")\n                srcLen = r.vcdVar()\n                srcPos = r.vcdVar()\n                if (srcLen.toLong() + srcPos.toLong() > rom.size.toLong()) throw UserError(WRONG_ROM)\n            }\n            val deltaLen = r.vcdVar()\n            val deltaEnd = r.pos + deltaLen\n            val tgtLen = r.vcdVar()\n            val ind = r.u8()\n            if (ind != 0) throw UserError(SECONDARY)\n            val dataLen = r.vcdVar()\n            val instLen = r.vcdVar()\n            val addrLen = r.vcdVar()\n            var adler = -1L\n            if ((win and 4) != 0) {\n                adler = ((r.u8().toLong() shl 24) or (r.u8().toLong() shl 16) or (r.u8().toLong() shl 8) or r.u8().toLong())\n            }\n            val data = Reader(p, r.pos)\n            val inst = Reader(p, r.pos + dataLen)\n            val addr = Reader(p, r.pos + dataLen + instLen)\n            val instEnd = r.pos + dataLen + instLen\n            if (addrLen < 0 || instEnd + addrLen > p.size) throw UserError("El archivo de parche esta incompleto o danado.")\n            r.pos = deltaEnd\n            val tgt = ByteArray(tgtLen)\n            var t = 0\n            val near = IntArray(4)\n            var nextSlot = 0\n            val same = IntArray(768)\n            while (inst.pos < instEnd) {\n                val idx = inst.u8()\n                for (half in 0..1) {\n                    val type = if (half == 0) type1[idx] else type2[idx]\n                    var size = if (half == 0) size1[idx] else size2[idx]\n                    val mode = if (half == 0) mode1[idx] else mode2[idx]\n                    if (type != TYPE_NOOP) {\n                        if (size == 0) size = inst.vcdVar()\n                        if (type == TYPE_ADD) {\n                            for (k in 0 until size) {\n                                tgt[t] = data.u8().toByte()\n                                t++\n                            }\n                        } else if (type == TYPE_RUN) {\n                            val b = data.u8().toByte()\n                            for (k in 0 until size) {\n                                tgt[t] = b\n                                t++\n                            }\n                        } else {\n                            val here = srcLen + t\n                            var a: Int\n                            if (mode == 0) a = addr.vcdVar()\n                            else if (mode == 1) a = here - addr.vcdVar()\n                            else if (mode < 6) a = near[mode - 2] + addr.vcdVar()\n                            else a = same[(mode - 6) * 256 + addr.u8()]\n                            if (a < 0) throw UserError("El archivo de parche esta danado.")\n                            near[nextSlot] = a\n                            nextSlot = (nextSlot + 1) and 3\n                            same[a % 768] = a\n                            for (k in 0 until size) {\n                                tgt[t] = if (a < srcLen) rom[srcPos + a] else tgt[a - srcLen]\n                                t++\n                                a++\n                            }\n                        }\n                    }\n                }\n            }\n            if (t != tgtLen) throw UserError("El archivo de parche esta danado.")\n            if (adler >= 0) {\n                val ad = Adler32()\n                ad.update(tgt, 0, tgt.size)\n                if (ad.value != adler) {\n                    throw UserError("El parche no se pudo aplicar bien a esta ROM (suma de control distinta). Proba con la ROM original sin modificar o parchea con hack64.net.")\n                }\n            }\n            sink.write(tgt, 0, t)\n        }\n    }\n}\n'

# --- comprobaciones antes de escribir nada
target = CONV / "RomPatcher.kt"
assert not target.exists(), "RomPatcher.kt ya existe (parche ya aplicado?)"
act = CONV / "ConvertActivity.kt"
s = act.read_text()

s = sub(s,
"        form.addView(label(\"Storage\", 14f, Palette.SUBTLE).apply { setPadding(0, dp(16), 0, 0) })\n",
"        form.addView(patchSection())\n"
"        form.addView(label(\"Storage\", 14f, Palette.SUBTLE).apply { setPadding(0, dp(16), 0, 0) })\n", "form")

s = sub(s, "    private fun applyStorageDefault() {\n", '''    /** Mods de ROM: si junto a la ROM hay parches (.ips .bps .ups .xdelta) deja aplicar uno y convertir el resultado. */
    private fun patchSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dir = romFile.parentFile ?: return box
        if (romFile.ext == "zip") return box
        val patches = dir.listFiles().orEmpty()
            .filter { it.isFile && it.ext in RomPatcher.EXTENSIONS }
            .sortedBy { it.name.lowercase() }
        if (patches.isEmpty()) return box
        box.addView(
            button("Aplicar un parche de mod...") { choosePatch(patches) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(14) },
        )
        box.addView(label("Crea una copia parcheada de la ROM; la original no se toca.", 12f, Palette.SUBTLE))
        return box
    }

    private fun choosePatch(patches: List<File>) {
        if (busy) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Elegi el parche")
            .setItems(patches.map { it.name }.toTypedArray()) { _, which -> applyPatch(patches[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyPatch(patch: File) {
        busy = true
        android.widget.Toast.makeText(this, "Aplicando el parche...", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { patchedCopy(patch) } }
            busy = false
            result.onSuccess { out ->
                android.widget.Toast.makeText(this@ConvertActivity, "Listo: " + out.name, android.widget.Toast.LENGTH_LONG).show()
                startActivity(Intent(this@ConvertActivity, ConvertActivity::class.java).putExtra(EXTRA_ROM, out.absolutePath))
                finish()
            }.onFailure { e ->
                val msg = if (e is UserError) e.message
                else if (e is OutOfMemoryError) "No hay memoria para parchear una ROM tan grande. Usa hack64.net en el navegador."
                else "No se pudo aplicar el parche: " + e.message
                android.app.AlertDialog.Builder(this@ConvertActivity).setMessage(msg).setPositiveButton("OK", null).show()
            }
        }
    }

    /** Escribe "<nombre> (parcheado).<ext>" junto a la ROM (o en la carpeta de la app si ahi no se puede escribir). */
    private fun patchedCopy(patch: File): File {
        if (romFile.length() > RomPatcher.MAX_BYTES) {
            throw UserError("La ROM es demasiado grande para parchearla en el celular. Usa hack64.net en el navegador.")
        }
        val rom = romFile.readBytes()
        val bytes = patch.readBytes()
        val name = romFile.nameWithoutExtension + " (parcheado)." + romFile.extension
        var out = File(romFile.parentFile, name)
        try {
            out.outputStream().buffered().use { RomPatcher.apply(rom, bytes, it) }
        } catch (e: java.io.IOException) {
            out = File(getExternalFilesDir(null) ?: cacheDir, name)
            out.outputStream().buffered().use { RomPatcher.apply(rom, bytes, it) }
        }
        return out
    }

    private fun applyStorageDefault() {
''', "fns")

# --- .cia en la 3DS (experimental): solo si la lista de extensiones tiene el formato esperado
sp = pathlib.Path("builder/systems.json")
js = sp.read_text()
cia_note = "ok"
anchor = '        "cxi",\n        "3dsx",'
if '"cia"' in js:
    cia_note = "ya estaba"
elif js.count(anchor) == 1:
    js = js.replace(anchor, '        "cxi",\n        "cia",\n        "3dsx",')
    sp.write_text(js)
    import json; json.loads(js)
else:
    cia_note = "no se encontro el bloque de la 3DS: .cia NO agregado"

target.write_text(PATCHER)
act.write_text(s)
print("OK  (cia:", cia_note + ")")
