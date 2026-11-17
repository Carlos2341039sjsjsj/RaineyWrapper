from pathlib import Path
p = Path("app/src/main/java/com/raineywrapper/player/TouchOverlay.kt")
t = p.read_text()
viejo = """        invalidate()
    }
}

    fun saveControlStyle(style: Int) {"""
nuevo = """        invalidate()
    }

    fun saveControlStyle(style: Int) {"""
if t.count(viejo) == 1:
    t = t.replace(viejo, nuevo)
    # la llave de cierre de la clase pasa al final del archivo
    t = t.rstrip() + "\n}\n"
    p.write_text(t)
    print("FIX CLASE OK")
else:
    print("NO ENCONTRE (%d coincidencias), no toque nada" % t.count(viejo))
