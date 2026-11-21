import pathlib

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

p = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player/GameActivity.kt")
s = p.read_text()

# 1) tambien se ofrece en la 3DS (GPU basica o media)
s = sub(s,
'        if (config.system !in listOf("psp", "wii", "gamecube", "gc")) return',
'        if (config.system !in listOf("psp", "wii", "gamecube", "gc", "3ds")) return', "offer")

# 2) que hace el modo ligero en la 3DS: resolucion 1x + rendimiento alto
s = sub(s,
'''        if (config.system == "psp") e.putInt(PREF_PSP_GFX, if (on) 1 else 0)
        else e.putInt(PREF_DOLPHIN_OPT, if (on) 0 else 1)''',
'''        if (config.system == "psp") e.putInt(PREF_PSP_GFX, if (on) 1 else 0)
        else if (config.system == "3ds") e.putBoolean(PREF_3DS_PERF, on).putInt(PREF_3DS_RES, 0)
        else e.putInt(PREF_DOLPHIN_OPT, if (on) 0 else 1)''', "setLite")

# 3) texto del aviso
s = sub(s,
'        val details = if (config.system == "psp") {',
'''        val details = if (config.system == "3ds") {
            "- Resolucion interna 1x (nativa)\\n" +
                "- Rendimiento alto (menos precision en el GPU, puede haber pequenos fallos graficos)\\n" +
                "- La 3DS es muy exigente: aun asi puede no ir fluido en juegos pesados\\n\\n"
        } else if (config.system == "psp") {''', "details")

# 4) el interruptor "Modo ligero" del menu tambien aparece en la 3DS
s = sub(s,
'''            menu.refresh()
        }.takeIf { config.system in listOf("psp", "wii", "gamecube", "gc") },
        GameMenu.Item({ "Controllers: "''',
'''            menu.refresh()
        }.takeIf { config.system in listOf("psp", "wii", "gamecube", "gc", "3ds") },
        GameMenu.Item({ "Controllers: "''', "menu")
p.write_text(s)
print("OK")
