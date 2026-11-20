import pathlib

p = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player/GameActivity.kt")
s = p.read_text()
old = '''            Variable("melonds_jit_enable", "enabled"),
        ) + (if'''
new = '''            Variable("melonds_jit_enable", "enabled"),
            // Pantalla tactil: el modo "Touch" lee el puntero que manda el reproductor (el modo por
            // defecto lee un mouse y por eso el dedo no hacia nada). Nombre comprobado con `strings`.
            Variable("melonds_touch_mode", "Touch"),
        ) + (if'''
assert s.count(old) == 1
p.write_text(s.replace(old, new))
print("OK")
