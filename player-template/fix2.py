import pathlib
base = pathlib.Path.home() / "RaineyWrapper/player-template/app/src/main/java/com/raineywrapper/player"
g = base / "Graphics.kt"
t = g.read_text()
if "data class Variable" not in t:
    t = t.replace("data class Level", "data class Variable(val name: String, val value: String)\n\ndata class Level", 1)
    print("Variable agregada OK")
else:
    print("Variable ya existia")
g.write_text(t)
ga = (base / "GameActivity.kt").read_text()
print("multiplayer asignado?", "startedMultiplayer = multiplayerLevel()" in ga)
print("dolphin asignado?", "startedDolphin = dolphinLevel()" in ga)
