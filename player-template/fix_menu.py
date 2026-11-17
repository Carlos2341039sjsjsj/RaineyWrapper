import pathlib, re
p = pathlib.Path("app/src/main/java/com/raineywrapper/player/GameActivity.kt")
t = p.read_text()

# Busca desde el Item de Restart hasta el ) { restartGame() },
pat = re.compile(r"GameMenu\.Item\(\s*\{ when \{.*?\) \{ restartGame\(\) \},", re.DOTALL)
m = pat.search(t)
print("Encontrado:", bool(m))
if m:
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
    t = t[:m.start()] + nuevo + t[m.end():]
    p.write_text(t)
    print("FIX MENU OK")
else:
    print("NO ENCONTRE EL BLOQUE, no toque nada")
