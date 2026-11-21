import pathlib

c = pathlib.Path("libretrodroid/libretrodroid/src/main/cpp/libretrodroid.cpp")
s = c.read_text()

decl = "static bool raineyFlipY(bool bottomLeftOrigin);\n"
assert s.count(decl) == 1

# la definicion vieja (dentro del namespace) se quita
i = s.index("// RaineyWrapper: la app puede pedir la imagen invertida")
j = s.index("void LibretroDroid::onTouchEvent(float xAxis, float yAxis) {")
assert 0 < i < j and j - i < 800
s = s[:i] + s[j:]

# y se define completa arriba, fuera de cualquier namespace
func = '''// RaineyWrapper: la app puede pedir la imagen invertida (RAINEY_FLIP_Y=1) para los cores que la
// entregan boca abajo. Solo cambia como se dibuja; el toque se calcula aparte y no se afecta.
static bool raineyFlipY(bool bottomLeftOrigin) {
    const char* flag = getenv("RAINEY_FLIP_Y");
    return (flag != nullptr && flag[0] == '1') ? !bottomLeftOrigin : bottomLeftOrigin;
}
'''
s = s.replace(decl, func)
c.write_text(s)
print("OK")
