import pathlib

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

p = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player/GameActivity.kt")
s = p.read_text()

# audio de la 3DS siempre en HLE (el modo mas rapido; los LLE bajaban los FPS en las pruebas)
s = sub(s,
'        azaharAudioValue()?.let { v += Variable("citra_audio_emulation", it) }\n',
'        v += Variable("citra_audio_emulation", "hle")\n', "var")

# se quita el selector de audio del menu y sus funciones
s = sub(s,
'''        GameMenu.Item({
            "3DS audio: " + listOf("normal", "HLE (rapido)", "LLE (preciso)", "LLE multihilo")[azaharAudio()]
        }) {
            prefs().edit().putInt(PREF_3DS_AUDIO, (azaharAudio() + 1) % 4).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
''', '', "menu")

s = sub(s,
'''    private fun azaharAudio() = prefs().getInt(PREF_3DS_AUDIO, 0).coerceIn(0, 3)

    /** Valor de citra_audio_emulation (null = no tocar la opcion). */
    private fun azaharAudioValue(): String? = when (azaharAudio()) {
        1 -> "hle"
        2 -> "lle"
        3 -> "lle_multithread"
        else -> null
    }

''', '', "fun")
p.write_text(s)
print("OK")
