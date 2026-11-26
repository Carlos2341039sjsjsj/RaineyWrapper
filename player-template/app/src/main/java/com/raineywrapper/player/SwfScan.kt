package com.raineywrapper.player

import java.io.File
import java.util.zip.Inflater

/**
 * Lectura superficial de un .swf para sugerir como se juega: busca en el codigo del juego si usa el teclado
 * (keyCode, KeyboardEvent, Key.isDown...) y si menciona flechas. NO sabe que letras usa cada accion:
 * eso se resuelve con los perfiles por nombre, la pregunta inicial y el editor de botones.
 */
object SwfScan {
    class Hint(val keyboard: Boolean, val mouse: Boolean, val arrows: Boolean)

    private val KEY_WORDS = listOf("keyCode", "KeyboardEvent", "KEY_DOWN", "isDown", "onKeyDown", "getAscii", "getCode")
    private val MOUSE_WORDS = listOf("MouseEvent", "MOUSE_DOWN", "mouseX", "_xmouse", "onMouseDown", "onPress")

    fun scan(file: File): Hint? {
        try {
            if (!file.isFile || file.length() > 60_000_000L) return null
            val raw = file.readBytes()
            if (raw.size < 10) return null
            val data: ByteArray = when (raw[0].toInt().toChar()) {
                'F' -> raw
                'C' -> inflate(raw)
                else -> return null
            }
            val text = String(data, Charsets.ISO_8859_1)
            val keyboard = KEY_WORDS.any { text.contains(it) }
            val mouse = MOUSE_WORDS.any { text.contains(it) }
            val arrows = keyboard && text.contains("LEFT") && text.contains("RIGHT")
            return Hint(keyboard, mouse, arrows)
        } catch (e: Throwable) {
            return null
        }
    }

    private fun inflate(raw: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(raw, 8, raw.size - 8)
        val out = java.io.ByteArrayOutputStream(raw.size * 3)
        val buf = ByteArray(65536)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 200_000_000) break
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }
}
