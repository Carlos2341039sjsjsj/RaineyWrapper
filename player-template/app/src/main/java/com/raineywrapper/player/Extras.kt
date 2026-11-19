package com.raineywrapper.player

import com.swordfish.libretrodroid.Variable

class ExtraLevel(val label: String, val variables: List<Variable>)

object PpssppMultiplayer {
    /** Every level except "Apagado" also carries this install's own MAC address. */
    fun levels(): List<ExtraLevel> = base().mapIndexed { i, l ->
        if (i == 0) l else ExtraLevel(l.label, l.variables + macVariables())
    }

    /** 12 digits (0-9), set at startup; the core takes the MAC as ppsspp_change_mac_address01..12. */
    var mac: String? = null

    private fun macVariables(): List<Variable> {
        val m = mac ?: return emptyList()
        if (m.length != 12 || !m.all { it.isDigit() }) return emptyList()
        return m.mapIndexed { i, c -> Variable("ppsspp_change_mac_address" + (i + 1).toString().padStart(2, '0'), c.toString()) }
    }

    private fun base() = listOf(
        ExtraLevel("Apagado", emptyList()),
        ExtraLevel("Localhost", listOf(
            Variable("ppsspp_enable_wlan", "enabled"),
            Variable("ppsspp_change_pro_ad_hoc_server_address", "localhost"))),
        ExtraLevel("Servidor", listOf(
            Variable("ppsspp_enable_wlan", "enabled"),
            Variable("ppsspp_change_pro_ad_hoc_server_address", "socom.cc"))),
        ExtraLevel("Anfitrion LAN", listOf(
            Variable("ppsspp_enable_wlan", "enabled"),
            Variable("ppsspp_enable_builtin_pro_ad_hoc_server", "enabled"),
            Variable("ppsspp_change_pro_ad_hoc_server_address", "localhost"))),
        ExtraLevel("Servidor + UPnP", listOf(
            Variable("ppsspp_enable_wlan", "enabled"),
            Variable("ppsspp_enable_upnp", "enabled"),
            Variable("ppsspp_change_pro_ad_hoc_server_address", "socom.cc"))),
        ExtraLevel("IP propia", ipVariables()),
        // Anfitrion para VPN (ZeroTier): servidor interno + la IP propia escrita en "Multiplayer IP".
        // Sin IP definida cae a localhost, igual que "Anfitrion LAN".
        ExtraLevel("Anfitrion + IP propia", listOf(Variable("ppsspp_enable_builtin_pro_ad_hoc_server", "enabled")) +
            ipVariables().ifEmpty { listOf(
                Variable("ppsspp_enable_wlan", "enabled"),
                Variable("ppsspp_change_pro_ad_hoc_server_address", "localhost")) }),
    )

    /** IP of the host phone, typed by the person ("192.168.1.5"); null until set. */
    var customIp: String? = null

    // The core takes the server IP as 12 digit options: ...address01 .. ...address12.
    private fun ipVariables(): List<Variable> {
        val parts = customIp?.split('.')
        if (parts == null || parts.size != 4) return emptyList()
        val digits = parts.joinToString("") { it.padStart(3, '0') }
        if (digits.length != 12 || !digits.all { it.isDigit() }) return emptyList()
        return listOf(
            Variable("ppsspp_enable_wlan", "enabled"),
            Variable("ppsspp_change_pro_ad_hoc_server_address", "IP address"),
        ) + digits.mapIndexed { i, c ->
            Variable("ppsspp_pro_ad_hoc_server_address" + (i + 1).toString().padStart(2, '0'), c.toString())
        }
    }
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

// Claves comprobadas con `strings` sobre libppsspp_libretro_android.so.
// Valores comprobados: disabled/enabled, Auto/Nearest/Linear/Auto max quality.
object PpssppGraphics {
    private fun on(key: String) = Variable(key, "enabled")

    fun levels() = listOf(
        ExtraLevel("Normal", emptyList()),
        ExtraLevel("Rendimiento", listOf(
            on("ppsspp_auto_frameskip"),
            on("ppsspp_skip_gpu_readbacks"))),
        ExtraLevel("Rendimiento + ISO en RAM", listOf(
            on("ppsspp_auto_frameskip"),
            on("ppsspp_skip_gpu_readbacks"),
            on("ppsspp_cache_iso"))),
        ExtraLevel("Compatibilidad", listOf(on("ppsspp_skip_gpu_readbacks"))),
        ExtraLevel("Sin efectos", listOf(
            on("ppsspp_skip_buffer_effects"),
            on("ppsspp_skip_gpu_readbacks"))),
        ExtraLevel("Texturas mod", listOf(on("ppsspp_texture_replacement"))),
        ExtraLevel("Mod + compat.", listOf(
            on("ppsspp_texture_replacement"),
            on("ppsspp_skip_gpu_readbacks"))),
        ExtraLevel("Suavizado", listOf(
            on("ppsspp_texture_deposterize"),
            Variable("ppsspp_texture_filtering", "Auto max quality"))),
    )
}
