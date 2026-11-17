import pathlib
p = pathlib.Path("app/src/main/java/com/raineywrapper/player/GameActivity.kt")
t = p.read_text()
start = t.find("GameMenu.Item(")
# buscamos el que tiene Restart
start = t.find("GameMenu.Item(", t.find("Restart game to apply resolution") - 500)
# en realidad buscamos hacia atras el ultimo Item antes del when
start = t.rfind("GameMenu.Item(", 0, t.find("resolutionLevel() != startedResolution"))
end_marker = ") { restartGame() },"
end = t.find(end_marker, start)
print("start", start, "end", end)
if start != -1 and end != -1:
    end = end + len(end_marker)
    nuevo = """GameMenu.Item({ "Estilo de controles: " + controlStyleName() }) {
                val next = (controlStyle() + 1) % 3
                getSharedPreferences("rainey", MODE_PRIVATE).edit().putInt(PREF_CONTROL_STYLE, next).apply()
                overlay?.controlStyle = next
                overlay?.invalidate()
            },
            GameMenu.Item({ when {
                resolutionLevel() != startedResolution -> "Restart game to apply resolution"
                multiplayerLevel() != startedMultiplayer -> "Restart game to apply multiplayer"
                dolphinLevel() != startedDolphin -> "Restart game to apply optimize"
                wiiController() != startedWiiController -> "Restart game to apply controller"
                else -> "Restart game"
            } }, { gameRunning }) { restartGame() },"""
    t = t[:start] + nuevo + t[end:]
    p.write_text(t)
    print("FIX MENU OK")
else:
    print("NO ENCONTRE, no toque nada")
