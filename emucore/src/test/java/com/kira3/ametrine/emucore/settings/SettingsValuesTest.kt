package com.kira3.ametrine.emucore.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsValuesTest {
    @Test
    fun readsEachType() {
        val values = SettingsValues(EmulatorSettings())
        assertEquals(false, values["display.hBlend"])
        assertEquals(4, values["audio.resampleQuality"])
        assertEquals("auto", values["console.region"])
    }

    @Test
    fun writesByPath() {
        val values = SettingsValues(EmulatorSettings())
        assertEquals(true, values.with("display.hBlend", true).display.hBlend)
        assertEquals(8, values.with("audio.resampleQuality", 8).audio.resampleQuality)
        assertEquals("jp", values.with("console.region", "jp").console.region)
    }

    @Test
    fun wrongTypeIsIgnored() {
        assertEquals(EmulatorSettings(), SettingsValues(EmulatorSettings()).with("audio.resampleQuality", "fast"))
    }

    @Test(expected = Exception::class)
    fun unknownPathThrows() {
        SettingsValues(EmulatorSettings())["display.nope"]
    }
}
