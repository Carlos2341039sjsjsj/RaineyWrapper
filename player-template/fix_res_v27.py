from pathlib import Path
p = Path("app/src/main/java/com/raineywrapper/player/Graphics.kt")
t = p.read_text()
viejo = '"psp" -> listOf("480x272", "960x544", "1440x816")'
nuevo = '"psp" -> listOf("480x272", "960x544", "1440x816", "1920x1088")'
if t.count(viejo) == 1:
    Path("Graphics.kt.bak").write_text(t)
    p.write_text(t.replace(viejo, nuevo))
    print("FIX RES OK")
else:
    print("NO ENCONTRE (%d coincidencias), no toque nada" % t.count(viejo))
