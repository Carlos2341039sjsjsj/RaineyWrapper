import pathlib
base = pathlib.Path.home() / "RaineyWrapper/player-template/app/src/main/java/com/raineywrapper/player"
ga = base / "GameActivity.kt"
t = ga.read_text()
# Ppsspp y Dolphin son funciones, hay que llamarlas con ()
t = t.replace("PpssppMultiplayer.levels.getOrNull", "PpssppMultiplayer.levels().getOrNull")
t = t.replace("DolphinOptimize.levels.getOrNull", "DolphinOptimize.levels().getOrNull")
# Por si Dolphin tiene otro nombre en Graphics
t = t.replace("DolphinOptimize.levels.getOrNull(startedDolphin)", "DolphinOptimize.levels().getOrNull(startedDolphin)")
ga.write_text(t)
print("Fix 165 OK")
# Verificar
lines = open(ga).readlines()
print("".join(lines[164:166]))
# Ver como estan definidos en Graphics para confirmar
g = (base / "Graphics.kt").read_text()
import re
for name in ["PpssppMultiplayer", "DolphinOptimize"]:
    m = re.search(rf"{name}.*?levels.*?\n", g)
    print(name, "->", m.group(0)[:120] if m else "no encontrado")
