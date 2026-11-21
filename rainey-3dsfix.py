import pathlib

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

J = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player")

p = J / "AzaharOptions.kt"
t = p.read_text()
assert "PREF_3DS_TEX" not in t
p.write_text(t.rstrip("\n") + '\n\n// Filtro de texturas de la 3DS: 0 = ninguno, 1 = ScaleForce, 2 = xBRZ, 3 = Bicubic.\ninternal const val PREF_3DS_TEX = "azahar_tex"\n')

p = J / "GameActivity.kt"
s = p.read_text()

# 1) nombres reales de las opciones (comprobados con `strings` en libazahar_libretro_android.so)
# (el cache de shaders pasa a estar siempre activo, ver mas abajo; se quita la linea mal nombrada)
s = sub(s, '            v += Variable("citra_use_hw_shader_cache", "enabled")\n', '', "cache")
s = sub(s, 'v += Variable("citra_use_acc_mul", "disabled")',
           'v += Variable("citra_shaders_accurate_mul", "disabled")', "mul")

# 2) el cache de shaders en disco siempre activo (menos tirones al repetir una pista o escena)
# 3) filtro de texturas elegido en el menu
s = sub(s,
'        val perf = prefs().getBoolean(PREF_3DS_PERF, false)\n',
'''        v += Variable("citra_use_disk_shader_cache", "enabled")
        azaharTexLabel()?.let { v += Variable("citra_texture_filter", it) }
        val perf = prefs().getBoolean(PREF_3DS_PERF, false)
''', "extra")

s = sub(s, "    private fun azaharRes() =",
'''    private fun azaharTex() = prefs().getInt(PREF_3DS_TEX, 0).coerceIn(0, 3)

    /** Valor de citra_texture_filter (null = no tocar la opcion). */
    private fun azaharTexLabel(): String? = when (azaharTex()) {
        1 -> "ScaleForce"
        2 -> "xBRZ"
        3 -> "Bicubic"
        else -> null
    }

    private fun azaharRes() =''', "fun")

s = sub(s, '        GameMenu.Item({ "3DS rendimiento: "',
'''        GameMenu.Item({
            "3DS texturas: " + listOf("normal", "ScaleForce (suave)", "xBRZ (pesado)", "Bicubic")[azaharTex()]
        }) {
            prefs().edit().putInt(PREF_3DS_TEX, (azaharTex() + 1) % 4).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "3DS rendimiento: "''', "menu")
p.write_text(s)
print("OK")
