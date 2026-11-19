package com.raineywrapper.player

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20

/** Detecta la GPU del telefono y la clasifica de forma aproximada (0 = Basica, 1 = Media, 2 = Alta). */
object DeviceProfile {
    /** GL_RENDERER leido con un contexto EGL descartable; null si no se pudo. Llamar fuera del hilo principal. */
    fun gpuName(): String? {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        var ctx: EGLContext? = null
        var surf: EGLSurface? = null
        return try {
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(d, ver, 0, ver, 1)) return null
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(d, attrs, 0, configs, 0, 1, num, 0) || num[0] < 1) return null
            val cfg = configs[0] ?: return null
            ctx = EGL14.eglCreateContext(
                d, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            surf = EGL14.eglCreatePbufferSurface(
                d, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (!EGL14.eglMakeCurrent(d, surf, surf, ctx)) return null
            GLES20.glGetString(GLES20.GL_RENDERER)
        } catch (e: Throwable) {
            null
        } finally {
            try {
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                surf?.let { EGL14.eglDestroySurface(d, it) }
                ctx?.let { EGL14.eglDestroyContext(d, it) }
            } catch (e: Throwable) {
            }
        }
    }

    /** Estimacion por familia de GPU. Una GPU desconocida cuenta como Media. */
    fun tier(gpu: String?): Int {
        val g = gpu?.lowercase() ?: return 1
        Regex("mali-g(\\d+)").find(g)?.let { m ->
            val n = m.groupValues[1].toInt()
            return if (n <= 52) 0 else if (n <= 76) 1 else 2
        }
        if ("immortalis" in g || "xclipse" in g) return 2
        if ("mali-t" in g || "mali-4" in g || "powervr" in g) return 0
        Regex("adreno.*?(\\d{3})").find(g)?.let { m ->
            val n = m.groupValues[1].toInt()
            return if (n <= 540 || n in 600..616) 0 else if (n < 640) 1 else 2
        }
        return 1
    }

    fun tierName(tier: Int) = when (tier) { 0 -> "Basica"; 1 -> "Media"; else -> "Alta" }
}
