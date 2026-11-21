import pathlib
p = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player/GameActivity.kt")
t = p.read_text()
if 'c++_shared' in t:
    print("Ya estaba parcheado")
else:
    old = "override fun onCreate(savedInstanceState: Bundle?"
    # buscamos la linea completa del onCreate
    lines = t.splitlines()
    for i, l in enumerate(lines):
        if "override fun onCreate" in l:
            # insertar despues de la llave {
            # si la llave esta en esa misma linea o en la siguiente, lo metemos despues
            insert_at = i+1
            # si la siguiente linea es solo "{", saltamos una mas
            if insert_at < len(lines) and lines[insert_at].strip() == "{":
                insert_at += 1
            lines.insert(insert_at, '        try { System.loadLibrary("c++_shared") } catch (e: Throwable) { android.util.Log.w("Rainey", "c++_shared no cargo: $e") }')
            break
    p.write_text("\n".join(lines))
    print("OK GameActivity parcheado")
