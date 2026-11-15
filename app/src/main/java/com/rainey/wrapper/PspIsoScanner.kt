package com.rainey.wrapper

import java.io.File
import java.io.RandomAccessFile

object PspIsoScanner {
    val senalesMultiplayer = listOf(
        "sceNetAdhoc",
        "sceNetAdhocMatching",
        "sceNetAdhocctl",
        "sceNet",
        "sceNetInet",
        "AdhocMatching",
        "proAdhocServer",
        "MULTIPLAYER",
        "ADHOC"
    )

    fun escanearIso(rutaIso: String): ResultadoScan {
        try {
            val archivo = File(rutaIso)
            if (!archivo.exists()) {
                return ResultadoScan(false, "No se encontro la ISO")
            }
            val raf = RandomAccessFile(archivo, "r")
            val buffer = ByteArray(1024 * 1024)
            var totalLeido: Long = 0
            val maxLeer = 50 * 1024 * 1024
            while (totalLeido < maxLeer && totalLeido < archivo.length()) {
                val leido = raf.read(buffer)
                if (leido <= 0) break
                val texto = String(buffer, 0, leido, Charsets.ISO_8859_1)
                for (senal in senalesMultiplayer) {
                    if (texto.contains(senal, ignoreCase = true)) {
                        raf.close()
                        return ResultadoScan(true, "Detectado: $senal")
                    }
                }
                totalLeido += leido
            }
            raf.close()
            return ResultadoScan(false, "Sin multiplayer")
        } catch (e: Exception) {
            return ResultadoScan(false, "Error: ${e.message}")
        }
    }

    fun debeMostrarMultiplayer(rutaIso: String): Boolean {
        return escanearIso(rutaIso).tieneMultiplayer
    }

    data class ResultadoScan(
        val tieneMultiplayer: Boolean,
        val detalle: String
    )
}
