import pathlib

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

J = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player")

p = J / "AzaharOptions.kt"
t = p.read_text()
assert "PREF_3DS_RES" not in t
p.write_text(t.rstrip("\n") + '\n\n// Resolucion interna de la 3DS: 0 = 1x (nativa), 1 = 2x, 2 = 3x, 3 = 4x.\ninternal const val PREF_3DS_RES = "azahar_res"\n')

p = J / "GameActivity.kt"
s = p.read_text()

# la resolucion elegida (el preset "alto" sigue forzando 1x)
s = sub(s,
'''        if (prefs().getBoolean(PREF_3DS_PERF, false)) {
            v += Variable("citra_resolution_factor", "1x (Native)")
''',
'''        val perf = prefs().getBoolean(PREF_3DS_PERF, false)
        v += Variable("citra_resolution_factor", if (perf) "1x (Native)" else azaharResLabel())
        if (perf) {
''', "extra")

s = sub(s, "    /**\n     * Diseno de pantallas",
'''    private fun azaharRes() = prefs().getInt(PREF_3DS_RES, 0).coerceIn(0, 3)

    private fun azaharResLabel() = if (azaharRes() == 0) "1x (Native)" else "${azaharRes() + 1}x"

    /**
     * Diseno de pantallas''', "fun")

s = sub(s, '        GameMenu.Item({ "3DS rendimiento: "',
'''        GameMenu.Item({
            "3DS resolucion: " + (if (azaharRes() == 0) "1x (nativa)" else "${azaharRes() + 1}x") +
                (if (prefs().getBoolean(PREF_3DS_PERF, false)) " (el modo alto fuerza 1x)" else "")
        }) {
            prefs().edit().putInt(PREF_3DS_RES, (azaharRes() + 1) % 4).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "3DS rendimiento: "''', "menu")
p.write_text(s)
print("OK")
