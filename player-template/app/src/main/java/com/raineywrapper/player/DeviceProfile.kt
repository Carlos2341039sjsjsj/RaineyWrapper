package com.raineywrapper.player

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * Detecta la GPU y la clasifica por su nombre (GL_RENDERER, p. ej. "Mali-G68 MC4", "Adreno (TM) 619").
 * Nivel: 0 muy debil, 1 debil, 2 media, 3 fuerte, 4 tope de gama; -1 si no se conoce.
 * Las reglas se revisan en orden y gana la primera que coincide.
 */
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

    /** [family]: expresion regular sobre el nombre en minusculas; [from]..[to]: su numero de tres cifras (0..0 = cualquiera). */
    private class Rule(val family: String, val from: Int, val to: Int, val tier: Int)

    private fun mali(model: String, tier: Int) = Rule("mali-$model(?!\\d)", 0, 0, tier)

    private val rules = listOf(
        // Qualcomm Adreno
        Rule("adreno", 300, 529, 0), Rule("adreno", 530, 539, 1), Rule("adreno", 540, 599, 2),
        Rule("adreno", 600, 612, 0), Rule("adreno", 613, 629, 1), Rule("adreno", 630, 649, 2),
        Rule("adreno", 650, 699, 3), Rule("adreno", 700, 712, 1), Rule("adreno", 713, 729, 2),
        Rule("adreno", 730, 739, 3), Rule("adreno", 740, 899, 4),
        // Arm Mali (Midgard, Bifrost, Valhall, 5.a gen) e Immortalis
        Rule("mali-g1-ultra", 0, 0, 4), Rule("mali-g1-premium", 0, 0, 3), Rule("mali-g1-pro", 0, 0, 2),
        mali("g310", 0), mali("g510", 1), mali("g620", 2),
        Rule("mali-t\\d", 0, 0, 0), Rule("mali-4\\d\\d", 0, 0, 0),
        mali("g31", 0), mali("g51", 0), mali("g52", 0), mali("g57", 0), mali("g71", 1), mali("g72", 1),
        mali("g68", 1), mali("g76", 2), mali("g77", 2), mali("g78", 2), mali("g610", 2), mali("g615", 2),
        mali("g710", 3), mali("g715", 3), mali("g720", 3), mali("g725", 3), mali("g925", 4),
        Rule("immortalis", 0, 0, 4),
        // Samsung Xclipse (AMD RDNA)
        Rule("xclipse", 500, 599, 1), Rule("xclipse", 900, 929, 3), Rule("xclipse", 930, 999, 4),
        // Imagination PowerVR
        Rule("\\bg[em]?\\d{4}", 0, 0, 0), Rule("bxe", 0, 0, 0), Rule("bxm", 0, 0, 1), Rule("dxt", 0, 0, 2),
        Rule("powervr", 0, 0, 0),
        // Huawei
        Rule("maleoon", 0, 0, 2),
    )

    /** Nucleos de un Mali ("MC2", "MP4"); 0 si no lo dice. */
    private fun maliCores(name: String): Int = Regex("MC(\\d+)|MP(\\d+)", RegexOption.IGNORE_CASE).find(name)
        ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 0

    /** Nivel 0..4 por el nombre de la GPU; -1 si no se conoce. Un Mali con 1-2 nucleos baja un nivel; con 10 o mas sube uno. */
    fun fullTier(gpu: String?): Int {
        if (gpu.isNullOrBlank()) return -1
        val n = gpu.lowercase().replace("(tm)", "").replace(Regex("\\s+"), " ").trim()
        val number = Regex("(\\d{3})").find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        for (r in rules) {
            if (!Regex(r.family).containsMatchIn(n)) continue
            if ((r.from == 0 && r.to == 0) || number in r.from..r.to) {
                var t = r.tier
                if (n.startsWith("mali")) {
                    val cores = maliCores(gpu)
                    if (cores in 1..2 && t > 0) t--
                    if (cores >= 10 && t < 4) t++
                }
                return t
            }
        }
        return -1
    }

    /** Las GPU mas flojas (nivel 0), salvo un Mali-G57 con 3 o mas nucleos, que aguanta mas. */
    fun weakChip(gpu: String?): Boolean {
        if (fullTier(gpu) != 0) return false
        val n = gpu?.lowercase() ?: return false
        return !(Regex("mali-g57(?!\\d)").containsMatchIn(n) && maliCores(gpu) !in 1..2)
    }

    /** Escala simple de siempre: 0 = Basica, 1 = Media, 2 = Alta (desconocida = Media). Basica = niveles 0 y 1. */
    fun tier(gpu: String?): Int = when (fullTier(gpu)) {
        -1 -> 1
        0, 1 -> 0
        2 -> 1
        else -> 2
    }

    fun tierName(tier: Int) = when (tier) { 0 -> "Basica"; 1 -> "Media"; else -> "Alta" }

    /** Texto para el aviso: "nivel 1 de 4, gama baja". */
    fun describe(gpu: String?): String {
        val t = fullTier(gpu)
        if (t < 0) return "GPU sin clasificar"
        val name = arrayOf("muy baja", "baja", "media", "alta", "tope de gama")[t]
        return "nivel $t de 4, gama $name"
    }
}
