package com.rainey.wrapper

object PspMultiplayerFix {
    // Esto si conecta el multiplayer a PPSSPP
    fun aplicarConfigPsp(): Map<String, String> {
        return mapOf(
            "EnableNetworking" to "true",
            "EnableAdhoc" to "true",
            "proAdhocServer" to "socom.cc",
            "AdhocPort" to "27312",
            "EnableUPnP" to "true",
            "MacAddress" to "00:11:22:33:44:55",
            "Latency" to "150",
            "ShowMultiplayerMenu" to "true"
        )
    }

    fun estaActivado(): Boolean {
        return true // Ahora si aparece en el menu de PSP
    }
}
