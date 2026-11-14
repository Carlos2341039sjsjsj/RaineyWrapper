package com.rainey.wrapper

import android.opengl.GLES20

object MaliProfiles {
    fun getProfile(): String {
        val gpu = try { GLES20.glGetString(GLES20.GL_RENDERER) ?: "" } catch (e: Exception) { "" }
        return when {
            gpu.contains("G51") || gpu.contains("G52") || gpu.contains("G72") || gpu.contains("G31") -> "MALI_BAJO"
            gpu.contains("G57") || gpu.contains("G68") -> "MALI_MEDIO"
            gpu.contains("G710") || gpu.contains("G715") || gpu.contains("G720") || gpu.contains("G615") -> "MALI_ALTO"
            gpu.contains("Mali") -> "MALI_BAJO"
            else -> "NORMAL"
        }
    }

    fun getSettings(profile: String): Map<String, String> {
        return when (profile) {
            "MALI_BAJO" -> mapOf(
                "InternalResolution" to "1",
                "Ubershaders" to "Hybrid",
                "AudioLatency" to "10",
                "AudioStretch" to "true",
                "Overclock" to "0.65",
                "TextureCacheAccuracy" to "Safe",
                "EFBCopies" to "Texture",
                "GPUTextureDecoding" to "true",
                "Anisotropic" to "1",
                "VertexRounding" to "true"
            )
            "MALI_MEDIO" -> mapOf(
                "InternalResolution" to "1.5",
                "Ubershaders" to "Hybrid",
                "AudioLatency" to "8",
                "AudioStretch" to "true",
                "Overclock" to "0.80",
                "TextureCacheAccuracy" to "Safe",
                "EFBCopies" to "Texture",
                "GPUTextureDecoding" to "true",
                "Anisotropic" to "2",
                "VertexRounding" to "true"
            )
            "MALI_ALTO" -> mapOf(
                "InternalResolution" to "2",
                "Ubershaders" to "Exclusive",
                "AudioLatency" to "6",
                "AudioStretch" to "true",
                "Overclock" to "1.0",
                "TextureCacheAccuracy" to "Safe",
                "EFBCopies" to "Texture",
                "GPUTextureDecoding" to "false",
                "Anisotropic" to "4",
                "VertexRounding" to "true"
            )
            else -> mapOf()
        }
    }
}
