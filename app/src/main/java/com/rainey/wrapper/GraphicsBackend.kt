package com.rainey.wrapper

object GraphicsBackend {
    enum class Backend(val id: String, val nombre: String) {
        VULKAN("Vulkan", "Vulkan - Recomendado Mali"),
        OPENGL_ES("OpenGLES", "OpenGL ES - Compatibilidad")
    }
    var backendActual: Backend = Backend.VULKAN
    fun getBackendId(): String {
        return backendActual.id
    }
    fun cambiarA(backend: Backend) {
        backendActual = backend
    }
    fun getRecomendado(gameId: String): Backend {
        return when (gameId) {
            "GM4E01" -> Backend.VULKAN
            "G8ME01" -> Backend.VULKAN
            "GALE01" -> Backend.VULKAN
            "GZLE01" -> Backend.OPENGL_ES
            else -> Backend.VULKAN
        }
    }
}
