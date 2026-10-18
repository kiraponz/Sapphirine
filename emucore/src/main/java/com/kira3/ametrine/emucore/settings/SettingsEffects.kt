package com.kira3.ametrine.emucore.settings

/** When a changed setting reaches a running game. Everything not listed applies right away. */
object SettingsEffects {
    /**
     * Dotted paths of the settings that only take effect when a game next boots: the ss module
     * reads them as it loads the game (android_host.cpp sends the rest of them on at once).
     */
    val appliesOnNextBoot: Set<String> = setOf(
        "console.region",
        "console.cart",
        "console.padType",
        "console.autoRtc",
        "console.language",
        "display.hOverscan",
        "display.hBlend",
        "display.lineStart",
        "display.lineEnd",
        "display.lineStartPal",
        "display.lineEndPal",
        "system.biosFilename",
        "system.backupRam",
        "system.backupRamFile",
        "output.customDriverId",
        "output.turnipUbwcHint",
    )
}
