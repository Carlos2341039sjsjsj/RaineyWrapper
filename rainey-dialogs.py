import pathlib

def sub(s, old, new, name):
    assert s.count(old) == 1, (name, s.count(old))
    return s.replace(old, new)

p = pathlib.Path("player-template/app/src/main/java/com/raineywrapper/player/GameActivity.kt")
s = p.read_text()

# 1) Los avisos van de a uno: primero "Modo ligero" y, cuando se cierra (o si no corresponde), "Liberar espacio".
s = sub(s,
"            offerLiteMode()\n            offerMoveGame()\n",
"            offerLiteMode { offerMoveGame() }\n", "call")

s = sub(s, "    private fun offerLiteMode() {",
'''    /** Evita ofrecer "Liberar espacio" justo antes de reiniciar por haber activado el modo ligero. */
    private var liteRestarting = false

    private fun offerLiteMode(next: () -> Unit) {''', "sig")

s = sub(s,
'''gc", "3ds")) return
        if (prefs().getBoolean(PREF_LITE_ASKED, false)) return''',
'''gc", "3ds")) { next(); return }
        if (prefs().getBoolean(PREF_LITE_ASKED, false)) { next(); return }''', "early")

s = sub(s,
'''                prefs().edit().putBoolean(PREF_LITE_ASKED, true).apply()
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            showLiteDialog(gpu, tier)''',
'''                prefs().edit().putBoolean(PREF_LITE_ASKED, true).apply()
                next()
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            showLiteDialog(gpu, tier, next)''', "tier")

s = sub(s, "    private fun showLiteDialog(gpu: String?, tier: Int) {",
"    private fun showLiteDialog(gpu: String?, tier: Int, next: () -> Unit) {", "dlg-sig")

s = sub(s,
'''                prefs().edit().putBoolean(PREF_LITE_ASKED, true).commit()
                restartGame()''',
'''                prefs().edit().putBoolean(PREF_LITE_ASKED, true).commit()
                liteRestarting = true
                restartGame()''', "activar")

s = sub(s,
"            .setOnCancelListener { prefs().edit().putBoolean(PREF_LITE_ASKED, true).apply() }\n",
'''            .setOnCancelListener { prefs().edit().putBoolean(PREF_LITE_ASKED, true).apply() }
            .setOnDismissListener { if (!liteRestarting && !isFinishing && !isDestroyed) next() }
''', "dismiss")

# 2) "Liberar espacio" no se muestra si la pantalla ya se esta cerrando
s = sub(s, "        if (!canMoveGame()) return\n        if (prefs().getString(PREF_ASKED_MOVE, null) == config.rom) return",
"        if (isFinishing || isDestroyed) return\n        if (!canMoveGame()) return\n        if (prefs().getString(PREF_ASKED_MOVE, null) == config.rom) return", "move")
p.write_text(s)
print("OK")
