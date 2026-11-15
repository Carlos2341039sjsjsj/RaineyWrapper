package com.rainey.wrapper
import java.io.File
object JoiPlayDetector {
    fun queMotorEs(rutaCarpeta: String): String {
        val carpeta = File(rutaCarpeta)
        if (!carpeta.exists()) return "No es juego"
        val nombres = carpeta.listFiles()?.map { it.name.lowercase() } ?: return "No es juego"
        if (nombres.any { it.contains("rgss") }) return "RPG Maker XP/VX/Ace"
        if (nombres.any { it == "www" } || nombres.any { it == "package.json" }) return "RPG Maker MV/MZ"
        if (nombres.any { it == "rpg_rt.exe" } || nombres.any { it == "rpg_rt.ldb" }) return "RPG Maker 2000/2003"
        if (nombres.any { it == "game" } || nombres.any { it.contains(".rpy") } || nombres.any { it.contains(".rpyc") }) return "RenPy"
        if (nombres.any { it.contains("tyrano") }) return "TyranoBuilder"
        if (nombres.any { it == "index.html" }) return "HTML / Web"
        if (nombres.any { it == "data" } && nombres.any { it == "audio" }) return "RPG General"
        return "No es juego"
    }
    fun debeMostrarJoiPlay(ruta: String): Boolean {
        return queMotorEs(ruta) != "No es juego"
    }
}
