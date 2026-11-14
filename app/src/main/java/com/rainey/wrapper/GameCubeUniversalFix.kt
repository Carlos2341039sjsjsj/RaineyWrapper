package com.rainey.wrapper

object GameCubeUniversalFix {
    // Fix para TODOS los juegos de GameCube en Mali G52 / A12
    // Arregla texturas negras + audio lageado + FPS bajos

    fun getUniversalSettings(): Map<String, String> {
        return mapOf(
            // VIDEO - Base para todos
            "GFXBackend" to "Vulkan",
            "InternalResolution" to "1",
            "ShaderCompilationMode" to "1", // Ubershaders
            "EFBCopiesToTexture" to "true",
            "EFBCopiesToRAM" to "false",
            "EFBToTextureEnable" to "true",
            "TextureCacheAccuracy" to "0", // Fast - arregla lo negro
            "EnablePixelLighting" to "false",
            "PerPixelDepth" to "false",
            "DisableXFB" to "true",
            "SkipDuplicateXFBs" to "true",
            "BackendMultithreading" to "true",
            "VSync" to "false",
            "ShowFPS" to "false",

            // AUDIO - Base para todos
            "AudioBackend" to "Cubeb",
            "AudioLatency" to "40",
            "AudioStretch" to "true",
            "EnableAudioStretch" to "true",
            "DSP_HLE" to "true",

            // CPU - Base para todos
            "CPUCore" to "JITARM64",
            "SyncOnSkipIdle" to "false",
            "SpeedLimit" to "100"
        )
    }

    // Ajustes extra solo si el juego lo necesita
    fun getGameOverride(gameId: String): Map<String, String> {
        return when (gameId) {
            "GM4E01" -> mapOf("TextureCacheAccuracy" to "0") // Mario Strikers - el mas pesado
            "G8ME01" -> mapOf("DisableXFB" to "true") // Smash Melee
            "GMSE01" -> mapOf("EFBToTextureEnable" to "true") // Mario Sunshine
            "GZLE01" -> mapOf("EnablePixelLighting" to "false") // Zelda Wind Waker
            "GALE01" -> mapOf("DisableXFB" to "true") // Mario Kart Double Dash
            else -> emptyMap()
        }
    }
}
