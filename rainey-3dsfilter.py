import pathlib

p = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player/GameActivity.kt")
s = p.read_text()
old = "?: ScreenFilter.SHARP"
assert s.count(old) == 1, s.count(old)
# En la 3DS el filtro por defecto suaviza los bordes (menos escalones al estirar 400x240); se puede cambiar en el menu.
s = s.replace(old, '?: (if (config.system == "3ds") ScreenFilter.UPSCALE else ScreenFilter.SHARP)')
p.write_text(s)
print("OK")
