import pathlib

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

J = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player")

p = J / "AzaharOptions.kt"
t = p.read_text()
assert "PREF_3DS_AUDIO" not in t
p.write_text(t.rstrip("\n") + '\n\n// Audio de la 3DS: 0 = normal (lo que decida el core), 1 = HLE (rapido), 2 = LLE (preciso), 3 = LLE multihilo.\ninternal const val PREF_3DS_AUDIO = "azahar_audio"\n')

p = J / "GameActivity.kt"
s = p.read_text()

# valores comprobados con `strings -n 3` en libazahar_libretro_android.so: hle, lle, lle_multithread
s = sub(s,
'        azaharTexLabel()?.let { v += Variable("citra_texture_filter", it) }\n',
'''        azaharTexLabel()?.let { v += Variable("citra_texture_filter", it) }
        azaharAudioValue()?.let { v += Variable("citra_audio_emulation", it) }
''', "extra")

s = sub(s, "    private fun azaharRes() =",
'''    private fun azaharAudio() = prefs().getInt(PREF_3DS_AUDIO, 0).coerceIn(0, 3)

    /** Valor de citra_audio_emulation (null = no tocar la opcion). */
    private fun azaharAudioValue(): String? = when (azaharAudio()) {
        1 -> "hle"
        2 -> "lle"
        3 -> "lle_multithread"
        else -> null
    }

    private fun azaharRes() =''', "fun")

s = sub(s, '        GameMenu.Item({ "3DS rendimiento: "',
'''        GameMenu.Item({
            "3DS audio: " + listOf("normal", "HLE (rapido)", "LLE (preciso)", "LLE multihilo")[azaharAudio()]
        }) {
            prefs().edit().putInt(PREF_3DS_AUDIO, (azaharAudio() + 1) % 4).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "3DS rendimiento: "''', "menu")
p.write_text(s)
print("OK")
