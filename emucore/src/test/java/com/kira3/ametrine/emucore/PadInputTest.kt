package com.kira3.ametrine.emucore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pad buttons' codes (PadButton.code) against what the core's devices take. */
class PadInputTest {
    @Test
    fun buttonsSitInTheGamepadWord() {
        // Every Saturn button is one its bit in the gamepad's 16-bit input; the core maps
        // them over to the 3D pad's word (android_host.cpp's conversion table).
        val buttons = PadButton.entries.filter { it != PadButton.Analog }
        assertEquals(buttons.size, buttons.map { it.code }.distinct().size)
        for (button in buttons) assertTrue(button.code in 0..15)
    }

    @Test
    fun analogIsPastTheGamepadBits() {
        // Nothing Saturn: code 16, past the gamepad's bits, where SetPadBind takes it as the
        // 3D pad's analog-mode toggle.
        assertEquals(16, PadButton.Analog.code)
    }
}
