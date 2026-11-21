import pathlib
p = pathlib.Path("libretrodroid/libretrodroid/build.gradle.kts")
t = p.read_text()
t = t.replace("c++_static", "c++_shared")
p.write_text(t)
print("OK cambiado a c++_shared")
print([l for l in t.splitlines() if "STL" in l])
