import pathlib, re
base = pathlib.Path.home() / "RaineyWrapper/player-template/app/src/main/java/com/raineywrapper/player"
g = base / "Graphics.kt"
t = g.read_text()
# 1. Borrar definiciones de adentro (con o sin data, con o sin indent)
t = re.sub(r'\n[ ]*data class Variable\(val name: String, val value: String\)', '', t)
t = re.sub(r'\n[ ]*(data )?class Level\(val label: String, val variable: Variable\)', '', t)
# 2. Insertar arriba, despues del package, si no esta ya arriba
head = t.split("object InternalResolution")[0]
if "data class Variable" not in head:
    t = t.replace("package com.raineywrapper.player", "package com.raineywrapper.player\n\ndata class Variable(val name: String, val value: String)\n\ndata class Level(val label: String, val variable: Variable)", 1)
    print("Movido a top-level OK")
else:
    print("Ya estaba arriba")
g.write_text(t)
# Mostrar como quedo el principio
print("".join(open(g).readlines()[:20]))
