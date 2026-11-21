import pathlib

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

J = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player")

# DS: audio mas liviano dentro de "DS rendimiento: alto" (valores comprobados con `strings` en melonds)
p = J / "GameActivity.kt"
s = p.read_text()
s = sub(s,
'''            Variable("melonds_jit_literal_optimisations", "enabled"),
        ) else emptyArray<Variable>())''',
'''            Variable("melonds_jit_literal_optimisations", "enabled"),
            // Audio mas liviano: sin interpolacion y 10 bits (suena un poco mas seco, pero gasta menos CPU).
            Variable("melonds_audio_interpolation", "None"),
            Variable("melonds_audio_bitrate", "10-bit"),
        ) else emptyArray<Variable>())''', "ds")
p.write_text(s)

# Wii / GameCube: DSP en modo rapido (HLE) en el preset "Rendimiento"
p = J / "Extras.kt"
s = p.read_text()
s = sub(s,
'''            off("dolphin_main_accurate_cpu_cache"),
        )),
        ExtraLevel("Equilibrado", listOf(''',
'''            off("dolphin_main_accurate_cpu_cache"),
            on("dolphin_dsp_hle"),
        )),
        ExtraLevel("Equilibrado", listOf(''', "dolphin")
p.write_text(s)
print("OK")
