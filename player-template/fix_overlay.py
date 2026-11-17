import pathlib
p = pathlib.Path("app/src/main/java/com/raineywrapper/player/GameActivity.kt")
t = p.read_text()
t = t.replace("touchOverlay?.controlStyle", "overlay?.controlStyle")
t = t.replace("touchOverlay?.invalidate()", "overlay?.invalidate()")
p.write_text(t)
print("FIX OVERLAY OK")
