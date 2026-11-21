import pathlib

J = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player")

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

# 1) preferencia nueva
p = J / "AzaharOptions.kt"
s = p.read_text()
assert "PREF_3DS_PERF" not in s
p.write_text(s.rstrip("\n") + '\n\n// "Rendimiento alto" de la 3DS (opciones de precision del GPU desactivadas).\ninternal const val PREF_3DS_PERF = "azahar_perf"\n')

p = J / "GameActivity.kt"
s = p.read_text()

# 2) "Pantallas DS" tambien en 3DS (horizontal / vertical)
i = s.index('GameMenu.Item({ "Pantallas DS: "')
j = s.index('.takeIf { config.system == "nds" }', i)
assert j - i < 400, (i, j)
s = s[:j] + '.takeIf { isDualScreen() }' + s[j + len('.takeIf { config.system == "nds" }'):]

# 3) menu: rendimiento de la 3DS
s = sub(s,
'''        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "Optimize: " + dolphinLabel() }) {''',
'''        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "3DS rendimiento: " + (if (prefs().getBoolean(PREF_3DS_PERF, false)) "alto" else "normal") }) {
            prefs().edit().putBoolean(PREF_3DS_PERF, !prefs().getBoolean(PREF_3DS_PERF, false)).commit()
            restartGame()
        }.takeIf { config.system == "3ds" },
        GameMenu.Item({ "Optimize: " + dolphinLabel() }) {''', "menu")

# 4) opciones del core: diseno de pantallas segun la orientacion + preset de rendimiento
s = sub(s,
"    private fun azaharVariables(): Array<Variable> = when (azaharApi()) {",
"    private fun azaharVariables(): Array<Variable> = azaharExtra() + azaharApiVars()\n\n    private fun azaharApiVars(): Array<Variable> = when (azaharApi()) {", "vars")

s = sub(s,
"    /** Con un renderizador distinto de Auto:",
'''    /**
     * Diseno de pantallas (lado a lado en horizontal) y, si se pidio, el preset de rendimiento.
     * Los valores del diseno y la resolucion salen del core de Citra (`strings`); en Azahar pueden
     * variar: si el core no reconoce un valor, lo ignora y queda el predeterminado.
     */
    private fun azaharExtra(): Array<Variable> {
        val v = mutableListOf(
            Variable("citra_layout_option", if (dsLandscape()) "Side by Side" else "Default Top-Bottom Screen"),
        )
        if (prefs().getBoolean(PREF_3DS_PERF, false)) {
            v += Variable("citra_resolution_factor", "1x (Native)")
            v += Variable("citra_use_cpu_jit", "enabled")
            v += Variable("citra_use_shader_jit", "enabled")
            v += Variable("citra_use_hw_shader_cache", "enabled")
            v += Variable("citra_use_acc_mul", "disabled")
        }
        return v.toTypedArray()
    }

    /** Con un renderizador distinto de Auto:''', "extra")

p.write_text(s)
print("OK")
