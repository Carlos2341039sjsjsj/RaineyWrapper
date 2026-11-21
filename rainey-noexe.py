import pathlib

p = pathlib.Path("converter/app/src/main/java/com/raineywrapper/converter/Games.kt")
s = p.read_text()

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

# Un .exe es un programa de Windows: avisar claro en vez de crear una app que no abre.
s = sub(s,
'''            val ext = file.ext
            if (ext == "zip") return resolveZip(context, file, systemOverride)
''',
'''            val ext = file.ext
            if (ext == "exe" || ext == "msi" || ext == "bat") {
                throw UserError(
                    "${file.name} es un programa de Windows (.$ext) y no se puede abrir en el celular.\\n\\n" +
                        "Solo se leen juegos web de RPG Maker MV/MZ (index.html) y de RPG Maker 2000/2003 (RPG_RT.ldb). " +
                        "Si tu juego es otro, busca una version para Android.",
                )
            }
            if (ext == "zip") return resolveZip(context, file, systemOverride)
''', "exe")

# "Juego de PC" (RPG Maker MV/MZ) solo acepta el archivo index.html
s = sub(s,
'            if (ext == "cue") return Sheet(file, system, parseSheet(file, CUE_FILES))',
'''            if (system.id == "rpgmv" && ext != "html" && ext != "htm") {
                throw UserError("Para este tipo de juego elegi el archivo index.html de su carpeta, no ${file.name}.")
            }
            if (ext == "cue") return Sheet(file, system, parseSheet(file, CUE_FILES))''', "html")
p.write_text(s)
print("OK")
