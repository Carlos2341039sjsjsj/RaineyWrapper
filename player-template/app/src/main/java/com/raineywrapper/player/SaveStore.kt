package com.raineywrapper.player

import android.content.Context
import android.util.Log
import java.io.File

/** App-private storage for in-game saves (SRAM / memory cards) and save states. */
class SaveStore(context: Context) {

    val savesDir = File(context.filesDir, "saves").apply { mkdirs() }
    val systemDir = File(context.filesDir, "system").apply { mkdirs() }
    private val sramFile = File(savesDir, "game.srm")
    private val stateFile = File(savesDir, "game.state")

    init {
        // Builds before linked mode named the save after the ROM asset ("rom.srm").
        val legacy = File(savesDir, "rom.srm")
        if (!sramFile.exists() && legacy.exists()) legacy.renameTo(sramFile)
    }

    fun readSram(): ByteArray? = sramFile.readIfPresent()

    fun writeSram(bytes: ByteArray) = write(sramFile, bytes)

    fun hasState() = stateFile.exists() && stateFile.length() > 0

    fun readState(): ByteArray? = stateFile.readIfPresent()

    fun writeState(bytes: ByteArray) = write(stateFile, bytes)

    private fun File.readIfPresent() = takeIf { it.exists() && it.length() > 0 }?.readBytes()

    @Synchronized
    private fun write(file: File, bytes: ByteArray): Boolean {
        // Some cores report empty data before the game has initialised it; never
        // overwrite a real save with nothing.
        if (bytes.isEmpty()) return false
        return try {
            val tmp = File(savesDir, file.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.e("RaineyWrapper", "Failed to write ${file.name}", e)
            false
        }
    }

    /**
     * Imports are staged as import.* and applied at startup, because the game autosaves
     * its SRAM when the app pauses and would overwrite a file copied while it runs.
     */
    fun applyPendingImports() {
        val pendingSram = File(savesDir, "import.srm")
        if (pendingSram.exists()) {
            sramFile.delete()
            pendingSram.renameTo(sramFile)
        }
        val pendingState = File(savesDir, "import.state")
        if (pendingState.exists()) {
            stateFile.delete()
            pendingState.renameTo(stateFile)
        }
    }

    /** Returns a message; it starts with "OK" when something was imported. */
    fun importFile(name: String, input: java.io.InputStream, system: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (system == "psp") {
            if (ext != "zip") return "En PSP hay que importar un .zip con la carpeta de guardado (SAVEDATA)"
            val count = unzipPsp(input)
            return if (count > 0) "OK: $count archivos importados" else "El zip esta vacio"
        }
        val target = when (ext) {
            "srm", "sav" -> File(savesDir, "import.srm")
            "state" -> File(savesDir, "import.state")
            else -> return "Archivo no soportado (usa .srm, .sav o .state)"
        }
        val tmp = File(savesDir, target.name + ".tmp")
        tmp.outputStream().use { input.copyTo(it) }
        if (tmp.length() == 0L) {
            tmp.delete()
            return "El archivo esta vacio"
        }
        target.delete()
        tmp.renameTo(target)
        return "OK: partida importada"
    }

    /** Extracts into <system>/PPSSPP/PSP/. Loose save folders go under SAVEDATA/. */
    private fun unzipPsp(input: java.io.InputStream): Int {
        val base = File(savesDir, "PSP").apply { mkdirs() }
        val baseCanon = base.canonicalPath
        var count = 0
        java.util.zip.ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    var path = entry.name.replace('\\', '/').removePrefix("/")
                    if (path.startsWith("PSP/")) path = path.removePrefix("PSP/")
                    val top = path.substringBefore('/')
                    if (top != "SAVEDATA" && top != "TEXTURES" && top != "GAME" && top != "Cheats") path = "SAVEDATA/$path"
                    val out = File(base, path)
                    // Zip-slip guard: never write outside the PSP folder.
                    if (out.canonicalPath.startsWith(baseCanon + File.separator)) {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        count++
                    }
                }
                entry = zip.nextEntry
            }
        }
        return count
    }

    /** Extracts a mod zip (texture pack, cheats) into the folder the core reads for [system]. */
    fun importMod(name: String, input: java.io.InputStream, system: String): String {
        if (!name.lowercase().endsWith(".zip")) return "El mod tiene que ser un .zip"
        val base = when (system) {
            "psp" -> File(savesDir, "PSP")
            "n64" -> File(systemDir, "Mupen64plus")
            "gamecube", "wii", "gc" -> File(savesDir, "dolphin-emu/User")
            else -> return "Los mods en zip todavia no estan soportados para este sistema"
        }
        base.mkdirs()
        val baseCanon = base.canonicalPath
        var count = 0
        var skipped = 0
        java.util.zip.ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val path = entry.name.replace('\\', '/').removePrefix("/")
                    val out = modTarget(system, path)?.let { File(base, it) }
                    // Zip-slip guard: never write outside the core's folder.
                    if (out != null && out.canonicalPath.startsWith(baseCanon + File.separator)) {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        count++
                    } else {
                        skipped++
                    }
                }
                entry = zip.nextEntry
            }
        }
        return when {
            count == 0 -> "No se reconocio la estructura del zip ($skipped archivos omitidos)"
            skipped > 0 -> "OK: $count archivos importados, $skipped omitidos"
            else -> "OK: $count archivos importados"
        }
    }

    /** Where a zip entry goes, relative to the core's folder; null when the layout isn't recognised. */
    private fun modTarget(system: String, path: String): String? {
        val first = path.substringBefore('/')
        val hasDir = '/' in path
        val gameId = Regex("[A-Z0-9]{6}([A-Z0-9]{2})?")
        return when (system) {
            "psp" -> when {
                first in setOf("TEXTURES", "SAVEDATA", "GAME", "Cheats") -> path
                hasDir && Regex("[A-Z]{4}[0-9]{5}").matches(first) -> "TEXTURES/$path"
                else -> null
            }
            "n64" -> when {
                first == "hires_texture" || first == "cache" -> path
                !hasDir && (path.endsWith(".hts", true) || path.endsWith(".htc", true)) -> "cache/$path"
                hasDir -> "hires_texture/$path"
                else -> null
            }
            else -> when {
                first == "Load" || first == "GameSettings" || first == "Config" -> path
                hasDir && gameId.matches(first) -> "Load/Textures/$path"
                !hasDir && path.endsWith(".ini", true) && gameId.matches(path.dropLast(4)) -> "GameSettings/$path"
                else -> null
            }
        }
    }
}
