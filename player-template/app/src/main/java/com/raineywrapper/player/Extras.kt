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

// Claves y valores comprobados con `strings` sobre libdolphin_libretro_android.so:
// booleanos = "enabled"/"disabled"; shaders = "Async (UberShaders)" / "Sync (UberShaders)".
object DolphinOptimize {
    private fun on(key: String) = Variable(key, "enabled")
    private fun off(key: String) = Variable(key, "disabled")

    fun levels() = listOf(
        ExtraLevel("Rendimiento", listOf(
            Variable("dolphin_shader_compilation_mode", "Async (UberShaders)"),
            on("dolphin_fastmem"),
            on("dolphin_main_cpu_thread"),
            on("dolphin_fast_depth_calculation"),
            on("dolphin_fast_texture_sampling"),
            on("dolphin_fast_disc_speed"),
            on("dolphin_efb_to_texture"),
            off("dolphin_main_accurate_cpu_cache"),
        )),
        ExtraLevel("Equilibrado", listOf(
            Variable("dolphin_shader_compilation_mode", "Async (UberShaders)"),
            on("dolphin_fastmem"),
            on("dolphin_main_cpu_thread"),
        )),
        ExtraLevel("Calidad", listOf(
            Variable("dolphin_shader_compilation_mode", "Sync (UberShaders)"),
            off("dolphin_fast_depth_calculation"),
            off("dolphin_fast_texture_sampling"),
        )),
    )
}
