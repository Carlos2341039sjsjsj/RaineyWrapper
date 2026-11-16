package com.raineywrapper.player

import com.swordfish.libretrodroid.Variable

class ExtraLevel(val label: String, val variables: List<Variable>)

object PpssppMultiplayer {
    fun levels() = listOf(
        ExtraLevel("Apagado", emptyList()),
        ExtraLevel("Localhost", listOf(
            Variable("ppsspp_enable_wlan", "enabled"),
            Variable("ppsspp_change_pro_ad_hoc_server_address", "localhost"))),
        ExtraLevel("Servidor", listOf(
            Variable("ppsspp_enable_wlan", "enabled"),
            Variable("ppsspp_change_pro_ad_hoc_server_address", "socom.cc"))),
    )
}

// UNVERIFIED: dolphin keys/values not checked against the core.
object DolphinOptimize {
    fun levels() = listOf(
        ExtraLevel("Rendimiento", listOf(Variable("dolphin_shader_compilation_mode", "3"))),
        ExtraLevel("Equilibrado", emptyList()),
        ExtraLevel("Calidad", emptyList()),
    )
}
