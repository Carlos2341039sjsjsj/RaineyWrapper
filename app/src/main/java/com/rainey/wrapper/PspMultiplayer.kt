package com.rainey.wrapper

object PspMultiplayer {
    // Multiplayer PSP - Solo PSP por ahora
    // GameCube / Wii / etc. se agregan despues
    const val ADHOC_SERVER = "socom.cc"
    const val ADHOC_PORT = 27312

    fun getPspNetSettings(): Map<String, String> {
        return mapOf(
            "EnableNetworking" to "true",
            "EnableAdhoc" to "true",
            "proAdhocServer" to ADHOC_SERVER,
            "AdhocPort" to ADHOC_PORT.toString(),
            "EnableUPnP" to "true",
            "Latency" to "150"
        )
    }

    fun getServers(): List<String> {
        return listOf(
            "socom.cc - Principal (Recomendado)",
            "myneighborsushicat.com - Alternativo",
            "coldbird.net - Backup"
        )
    }
}
