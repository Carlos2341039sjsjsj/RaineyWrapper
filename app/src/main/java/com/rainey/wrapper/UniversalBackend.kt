package com.rainey.wrapper

object UniversalBackend {
    // Esto aplica para TODAS las consolas, no solo GameCube
    enum class Backend(val id: String) {
        VULKAN("Vulkan"),
        OPENGL_ES("OpenGLES")
    }

    var actual: Backend = Backend.VULKAN

    fun aplicarPara(tipoConsola: String): String {
        // GameCube, PSP, N64, Wii, etc. usan el mismo backend que elegis
        return when (tipoConsola) {
            "GameCube" -> actual.id
            "PSP" -> actual.id
            "N64" -> actual.id
            "Wii" -> actual.id
            "PS2" -> actual.id
            else -> actual.id
        }
    }

    fun getNombre(): String {
        return if (actual == Backend.VULKAN) "Vulkan" else "OpenGL ES"
    }
}
