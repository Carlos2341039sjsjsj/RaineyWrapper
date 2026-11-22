import pathlib

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

J = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player")

opt = J / "AzaharOptions.kt"
o = opt.read_text()
assert "PREF_AUDIO_MODE" not in o
o = o.rstrip("\n") + '\n\n// Audio: 0 = automatico, 1 = baja latencia, 2 = estable (buffer mas grande, menos cortes).\ninternal const val PREF_AUDIO_MODE = "audio_mode"\n'

p = J / "GameActivity.kt"
s = p.read_text()

# 1) el reproductor deja de forzar baja latencia
s = sub(s, "            preferLowLatencyAudio = true\n",
           "            preferLowLatencyAudio = lowLatencyAudio()\n", "data")

# 2) logica: automatico = estable en los sistemas pesados (suelen ir por debajo de la velocidad real)
s = sub(s, "    private fun liteMode() = prefs().getBoolean(PREF_LITE, false)\n",
'''    private fun liteMode() = prefs().getBoolean(PREF_LITE, false)

    private fun audioMode() = prefs().getInt(PREF_AUDIO_MODE, 0).coerceIn(0, 2)

    /** Sistemas que en un celular de gama media/baja suelen ir por debajo de la velocidad real. */
    private fun isHeavySystem() = config.system in listOf("psp", "3ds", "nds", "wii", "gamecube", "gc", "n64", "ps2", "dreamcast")

    /** Baja latencia = buffer chico (4 cuadros): responde antes pero corta el sonido si el juego se atrasa. */
    private fun lowLatencyAudio(): Boolean = when (audioMode()) {
        1 -> true
        2 -> false
        else -> !isHeavySystem()
    }
''', "logic")

# 3) el modo ligero tambien deja el audio en estable (y al apagarlo vuelve a automatico)
s = sub(s, "        if (on) e.putInt(PREF_RESOLUTION, 0)\n",
           "        if (on) e.putInt(PREF_RESOLUTION, 0)\n        e.putInt(PREF_AUDIO_MODE, if (on) 2 else 0)\n", "lite")

# 4) opcion de menu para todos los sistemas
s = sub(s, '        GameMenu.Item({ "Quit" }) { finishAndRemoveTask() },\n',
'''        GameMenu.Item({
            "Audio: " + listOf(
                if (isHeavySystem()) "auto (estable)" else "auto (baja latencia)",
                "baja latencia",
                "estable (menos cortes)",
            )[audioMode()]
        }) {
            prefs().edit().putInt(PREF_AUDIO_MODE, (audioMode() + 1) % 3).commit()
            restartGame()
        },
        GameMenu.Item({ "Quit" }) { finishAndRemoveTask() },
''', "menu")

opt.write_text(o)
p.write_text(s)
print("OK")
