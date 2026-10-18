package com.kira3.ametrine.emulation.input

import com.kira3.ametrine.emucore.settings.DisplaySettings

/**
 * The width / height of the picture for [display]'s aspect ratio, or null when it has no fixed
 * shape (Stretch fills the screen).
 */
fun gameAspectOf(display: DisplaySettings): Float? = when (display.aspectRatio) {
    "Stretch" -> null
    "16:9" -> 16f / 9f
    else -> 4f / 3f
}
