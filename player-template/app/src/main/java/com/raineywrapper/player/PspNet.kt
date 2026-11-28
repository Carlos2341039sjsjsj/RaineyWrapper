package com.raineywrapper.player

import java.io.File
import java.io.FileInputStream

/**
 * Detector de multijugador ad-hoc de PSP: recorre el ISO buscando "sceNetAdhoc" (el modulo de red del juego)
 * y textos como "Ad Hoc". Si el EBOOT viene cifrado puede no encontrarlo: entonces queda "no detectado" y
 * el asistente se puede abrir a mano. Solo analiza .iso (un .cso esta comprimido).
 */
object PspNet {
    const val YES = "si"
    const val MAYBE = "posible"
    const val NO = "no"
    const val UNKNOWN = "desconocido"

    private val STRONG = "scenetadhoc".toByteArray()
    private val WEAK = listOf("ad hoc", "ad-hoc", "adhoc", "ad_hoc").map { it.toByteArray() }

    private fun matchAt(buf: ByteArray, i: Int, end: Int, p: ByteArray): Boolean {
        if (i + p.size > end) return false
        for (k in p.indices) {
            var b = buf[i + k].toInt()
            if (b in 65..90) b += 32
            if (b != p[k].toInt()) return false
        }
        return true
    }

    fun scan(file: File): String {
        if (!file.exists() || !file.name.endsWith(".iso", ignoreCase = true)) return UNKNOWN
        var weak = false
        try {
            FileInputStream(file).use { ins ->
                val chunk = 1 shl 20
                val buf = ByteArray(chunk + 32)
                var carry = 0
                while (true) {
                    val n = ins.read(buf, carry, chunk)
                    if (n <= 0) break
                    val end = carry + n
                    var i = 0
                    while (i < end) {
                        val c = buf[i].toInt() or 0x20
                        if (c == 115 && matchAt(buf, i, end, STRONG)) return YES
                        if (c == 97 && !weak) {
                            for (p in WEAK) if (matchAt(buf, i, end, p)) { weak = true; break }
                        }
                        i++
                    }
                    carry = minOf(end, 16)
                    System.arraycopy(buf, end - carry, buf, 0, carry)
                }
            }
        } catch (e: Throwable) {
            return UNKNOWN
        }
        return if (weak) MAYBE else NO
    }
}
