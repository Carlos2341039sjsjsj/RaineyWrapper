import pathlib, shutil, re, os, glob

root = pathlib.Path(".")
java = root/"libretrodroid/libretrodroid/src/main/java/com/swordfish/libretrodroid/LibretroDroid.java"
txt = java.read_text()

# 1. Cargar c++_shared ANTES que libretrodroid
old = 'System.loadLibrary("libretrodroid")'
new = 'System.loadLibrary("c++_shared");\n        System.loadLibrary("libretrodroid")'
if old in txt and "c++_shared" not in txt:
    txt = txt.replace(old, new)
    java.write_text(txt)
    print("OK LibretroDroid.java parcheado")
else:
    print("Ya estaba parcheado o no se encontro:", old in txt)

# 2. Buscar libc++_shared.so en el NDK 29.0.14206865
cands = []
for base in [os.environ.get("ANDROID_HOME",""), os.environ.get("ANDROID_SDK_ROOT",""), str(pathlib.Path.home()/"Android/Sdk"), "builder/rainey-tools", "builder/toolchain", "."]:
    cands += glob.glob(f"{base}/**/ndk/29.0.14206865/**/libc++_shared.so", recursive=True)
    cands += glob.glob(f"{base}/**/libc++_shared.so", recursive=True)

cands = list(dict.fromkeys([c for c in cands if "arm64" in c or "arm64-v8a" in c or "aarch64" in c]))
print("Encontrados:", cands[:5])

if cands:
    src = pathlib.Path(cands[0])
    dst_dir = root/"player-template/app/src/main/jniLibs/arm64-v8a"
    dst_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy(src, dst_dir/"libc++_shared.so")
    print("OK copiado a", dst_dir/"libc++_shared.so")
else:
    print("NO se encontro libc++_shared.so - pasa el path del NDK")
