package com.kira3.ametrine.emucore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionReplayTest {
    @Test
    fun readsCodeLines() {
        val check = ActionReplay.check("160ab9be ffff\n  D609C0A8:1234 \n360AB9BE 00ff")
        assertNull(check.badLine)
        assertEquals(listOf("160AB9BE FFFF", "D609C0A8 1234", "360AB9BE 00FF"), check.lines)
    }

    @Test
    fun skipsBlankLinesAndComments() {
        val check = ActionReplay.check("# infinite HP\n\n; note\n// more\n160AB9BE FFFF")
        assertNull(check.badLine)
        assertEquals(listOf("160AB9BE FFFF"), check.lines)
    }

    @Test
    fun pointsAtTheFirstBadLine() {
        val check = ActionReplay.check("160AB9BE FFFF\n960AB9BE 12345678\n160AB9BE 00012")
        assertEquals(2, check.badLine)
        assertEquals(listOf("160AB9BE FFFF"), check.lines)
    }

    @Test
    fun rulesOutUnknownCommands() {
        assertEquals(1, ActionReplay.check("a60AB9BE FFFF").badLine)
        assertEquals(listOf<String>(), ActionReplay.check("a60AB9BE FFFF").lines)
    }

    @Test
    fun rulesOutEightDigitValues() {
        assertEquals(1, ActionReplay.check("160AB9BE FFFFFFFF").badLine)
    }

    @Test
    fun rulesOutSixteenBitValuesOn8BitCodes() {
        assertEquals(1, ActionReplay.check("360AB9BE 1234").badLine)
        assertNull(ActionReplay.check("360AB9BE 00FF").badLine)
    }

    @Test
    fun rulesOutAddressesOutsideTheWorkRam() {
        // The Low work RAM starts at 0x00200000, so 0x02000000 is between the RAMs; the High
        // one's map ends after 0x07FFFFFF. The High RAM (0x160AB9BE) and its echo (0x16800000) are in.
        assertEquals(1, ActionReplay.check("12000000 0001").badLine)
        assertEquals(1, ActionReplay.check("14000000 FFFF").badLine)
        assertEquals(1, ActionReplay.check("18000000 FFFF").badLine)
        assertNull(ActionReplay.check("160AB9BE FFFF").badLine)
        assertNull(ActionReplay.check("16800000 FFFF").badLine)
    }

    @Test
    fun pointsAtAConditionWithNothingUnderIt() {
        assertEquals(1, ActionReplay.check("D60AB9BE FFFF").badLine)
        // The comments around the D change nothing: they are no lines of code.
        assertEquals(3, ActionReplay.check("160AB9BE FFFF\n# note\nD60AB9BE FFFF\n# more").badLine)
        assertNull(ActionReplay.check("D60AB9BE FFFF\n360AB9BE 0000").badLine)
    }
}
