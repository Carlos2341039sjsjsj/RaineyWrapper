import pathlib, re
base = pathlib.Path.home() / "RaineyWrapper/player-template/app/src/main/java/com/raineywrapper/player"
g = base / "Graphics.kt"
t = g.read_text()
# 1. Sacar nuestras clases de donde quedaron (antes de los imports)
t = t.replace("\ndata class Variable(val name: String, val value: String)\n\ndata class Level(val label: String, val variable: Variable)", "")
# 2. Sacar el import que choca - usamos nuestro Variable, no el de libretro
t = t.replace("import com.swordfish.libretrodroid.Variable\n", "")
# 3. Poner nuestras clases DESPUES del ultimo import
lines = t.splitlines(True)
last_import = max([i for i,l in enumerate(lines) if l.startswith("import ")])
lines.insert(last_import+1, "\n// Fix: clases propias despues de los imports\ndata class Variable(val name: String, val value: String)\n\ndata class Level(val label: String, val variable: Variable)\n")
t = "".join(lines)
g.write_text(t)
print("Graphics OK")
print("".join(open(g).readlines()[:15]))
# 4. Mostrar linea 165 de GameActivity para arreglarla
ga = (base / "GameActivity.kt").read_text().splitlines()
for i in range(160,170):
    print(f"{i+1}: {ga[i]}")
