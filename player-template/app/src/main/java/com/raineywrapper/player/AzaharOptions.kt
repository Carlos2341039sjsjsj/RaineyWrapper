package com.raineywrapper.player

// Claves de preferencias del renderizador de la 3DS (Azahar).
internal const val PREF_3DS_API = "azahar_api"
internal const val PREF_3DS_TRIAL = "azahar_api_trial"

// "Rendimiento alto" de la 3DS (opciones de precision del GPU desactivadas).
internal const val PREF_3DS_PERF = "azahar_perf"

// Invertir la imagen de la 3DS (algunos renderizadores la dibujan boca abajo).
internal const val PREF_3DS_FLIP = "azahar_flip"

// Resolucion interna de la 3DS: 0 = 1x (nativa), 1 = 2x, 2 = 3x, 3 = 4x.
internal const val PREF_3DS_RES = "azahar_res"

// Filtro de texturas de la 3DS: 0 = ninguno, 1 = ScaleForce, 2 = xBRZ, 3 = Bicubic.
internal const val PREF_3DS_TEX = "azahar_tex"

// Audio de la 3DS: 0 = normal (lo que decida el core), 1 = HLE (rapido), 2 = LLE (preciso), 3 = LLE multihilo.
internal const val PREF_3DS_AUDIO = "azahar_audio"

// Audio: 0 = automatico, 1 = baja latencia, 2 = estable (buffer mas grande, menos cortes).
internal const val PREF_AUDIO_MODE = "audio_mode"

// Reloj de la CPU emulada de la 3DS: 0 = 100% (normal), 1 = 80%, 2 = 70%, 3 = 60%. Menos = menos carga, juegos mas lentos si dependen de la CPU.
internal const val PREF_3DS_CLOCK = "azahar_clock"
