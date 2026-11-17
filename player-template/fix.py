import pathlib
base = pathlib.Path.home() / "RaineyWrapper/player-template/app/src/main/java/com/raineywrapper/player"
ga = base / "GameActivity.kt"
t = ga.read_text()

# 1. Declarar las 2 variables que faltan, al lado de startedWiiController
if "startedMultiplayer" not in t.split("startedResolution =")[0] or "private var startedMultiplayer" not in t:
    # buscamos la linea de startedWiiController declarada arriba
    t = t.replace(
        "private var startedWiiController",
        "private var startedMultiplayer = 0\n    private var startedDolphin = 1\n    private var startedWiiController",
        1
    )
    print("Declaracion OK")

# 2. Asignarlas donde se asigna startedResolution
if "startedMultiplayer = multiplayerLevel()" not in t:
    t = t.replace(
        "startedResolution = resolutionLevel()",
        "startedResolution = resolutionLevel()\n        startedMultiplayer = multiplayerLevel()\n        startedDolphin = dolphinLevel()",
        1
    )
    print("Asignacion OK")
else:
    print("Asignacion ya estaba")

ga.write_text(t)
print("LISTO fix GameActivity")

# 3. Revisar Graphics.kt - ver si Level esta definido
g = base / "Graphics.kt"
gt = g.read_text()
print("Graphics tiene 'class Level'?", "class Level" in gt or "data class Level" in gt)
print("Graphics tiene 'class Variable'?", "class Variable" in gt or "data class Variable" in gt)
# buscar donde esta definido Level en todo el proyecto
import subprocess, textwrap
print("--- Buscando Level en todo el proyecto ---")

