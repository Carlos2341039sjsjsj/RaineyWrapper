import pathlib

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

# 1) codigo nativo: invertir la imagen si lo pide la app (variable de entorno RAINEY_FLIP_Y=1)
c = pathlib.Path("libretrodroid/libretrodroid/src/main/cpp/libretrodroid.cpp")
s = c.read_text()
s = sub(s,
"        Environment::getInstance().isBottomLeftOrigin(),\n        Environment::getInstance().getScreenRotation(),",
"        raineyFlipY(Environment::getInstance().isBottomLeftOrigin()),\n        Environment::getInstance().getScreenRotation(),", "cpp-call")
s = sub(s, "void LibretroDroid::onTouchEvent(float xAxis, float yAxis) {",
'''// RaineyWrapper: la app puede pedir la imagen invertida (RAINEY_FLIP_Y=1) para los cores que la
// entregan boca abajo. Solo cambia como se dibuja; el toque se calcula aparte y no se afecta.
static bool raineyFlipY(bool bottomLeftOrigin) {
    const char* flag = getenv("RAINEY_FLIP_Y");
    return (flag != nullptr && flag[0] == '1') ? !bottomLeftOrigin : bottomLeftOrigin;
}

void LibretroDroid::onTouchEvent(float xAxis, float yAxis) {''', "cpp-helper")
# declaracion anticipada: la funcion se usa mas arriba de donde se define
s = "#include <cstdlib>\nstatic bool raineyFlipY(bool bottomLeftOrigin);\n" + s
c.write_text(s)

# 2) app: preferencia, variable de entorno antes de crear la vista y opcion de menu
J = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player")
p = J / "AzaharOptions.kt"
t = p.read_text()
assert "PREF_3DS_FLIP" not in t
p.write_text(t.rstrip("\n") + '\n\n// Invertir la imagen de la 3DS (algunos renderizadores la dibujan boca abajo).\ninternal const val PREF_3DS_FLIP = "azahar_flip"\n')

p = J / "GameActivity.kt"
s = p.read_text()
s = sub(s, "        val view = GLRetroView(this, data)\n",
'''        // Imagen invertida (3DS): el codigo nativo lee esta variable al crear el video.
        try {
            android.system.Os.setenv(
                "RAINEY_FLIP_Y",
                if (config.system == "3ds" && prefs().getBoolean(PREF_3DS_FLIP, false)) "1" else "0",
                true,
            )
        } catch (e: Exception) { }
        val view = GLRetroView(this, data)
''', "kt-env")
s = sub(s, '        GameMenu.Item({ "3DS rendimiento: "',
'''        GameMenu.Item({ "Invertir imagen: " + (if (prefs().getBoolean(PREF_3DS_FLIP, false)) "si" else "no") }) {
            prefs().edit().putBoolean(PREF_3DS_FLIP, !prefs().getBoolean(PREF_3DS_FLIP, false)).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "3DS rendimiento: "''', "kt-menu")
p.write_text(s)
print("OK")
