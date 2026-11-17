import re, shutil
from pathlib import Path
p = Path("app/src/main/java/com/raineywrapper/player/TouchOverlay.kt")
t = p.read_text()

cambios = [
  ('fun styledColor(faceLabel: String): Int {\n',
   'fun styledColor(faceLabel: String): Int {\n        if (controlStyle == 2) return android.graphics.Color.rgb(90, 95, 110)\n'),
  ('2 -> "Xbox"', '2 -> "Translucido"'),
  ('controlStyle == 2 && isPs', 'false'),
  ('    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {',
   '    private val fillLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255) }\n'
   '    private val fillLightPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 255) }\n'
   '    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {'),
  ('if (isPressed(c.keyCode)) fillPressed else fill',
   'if (isPressed(c.keyCode)) (if (controlStyle == 2) fillLightPressed else fillPressed) else (if (controlStyle == 2) fillLight else fill)'),
  ('if (stick.pointerId >= 0) fillPressed else fill',
   'if (stick.pointerId >= 0) (if (controlStyle == 2) fillLightPressed else fillPressed) else (if (controlStyle == 2) fillLight else fill)'),
]

malos = [(v[:40], t.count(v)) for v, n in cambios if t.count(v) != 1]
rx = re.compile(r'if \(controlStyle == 2\) \{(\s*)// Xbox por posicion')
if malos or len(rx.findall(t)) != 1:
    print("NO COINCIDE, no toque nada:", malos, len(rx.findall(t)))
else:
    shutil.copy(p, "TouchOverlay.kt.bak")
    for v, n in cambios:
        t = t.replace(v, n)
    t = rx.sub(r'if (false) {\1// Xbox por posicion', t)
    p.write_text(t)
    print("FIX TRANSLUCIDO OK")
