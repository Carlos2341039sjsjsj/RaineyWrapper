import pathlib

p = pathlib.Path(".github/workflows/build.yml")
s = p.read_text()
assert "libc++_shared" not in s
old = "      - name: Doctor\n"
assert s.count(old) == 1
new = '''      - name: libc++_shared (NDK)
        # La libreria nativa del reproductor la necesita en tiempo de ejecucion; en tu telefono la copiaba
        # fix_libcpp.py, pero esa copia local no se sube a GitHub (jniLibs no esta en git).
        run: |
          SRC=$(find "$ANDROID_HOME/ndk" -path "*aarch64-linux-android/libc++_shared.so" | head -1)
          echo "libc++_shared: $SRC"
          test -n "$SRC"
          mkdir -p player-template/app/src/main/jniLibs/arm64-v8a
          cp "$SRC" player-template/app/src/main/jniLibs/arm64-v8a/libc++_shared.so
      - name: Doctor
'''
p.write_text(s.replace(old, new))
print("OK")
