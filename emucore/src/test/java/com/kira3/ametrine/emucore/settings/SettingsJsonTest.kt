package com.kira3.ametrine.emucore.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {
    @Test
    fun roundTrips() {
        val settings = EmulatorSettings(
            console = ConsoleSettings(region = "na", cart = "extram4"),
            display = DisplaySettings(aspectRatio = "16:9", lineStart = 8),
        )
        assertEquals(settings, SettingsJson.decode(SettingsJson.parse(SettingsJson.format(SettingsJson.encode(settings)))))
    }

    @Test
    fun badValuesFallBackToDefaults() {
        val stored = SettingsJson.parse(
            """{"audio": {"resampleQuality": 1.5, "muted": "no", "bufferMs": 60}, "console": {"cart": 3}, "gone": {"x": 1}}""",
        )
        val settings = SettingsJson.decode(stored)
        assertEquals(4, settings.audio.resampleQuality)
        assertEquals(false, settings.audio.muted)
        assertEquals(60, settings.audio.bufferMs)
        assertEquals("auto", settings.console.cart)
    }

    @Test
    fun unknownChoiceValuesFallBackToDefaults() {
        // What the PS1 version stored under the same names.
        val stored = SettingsJson.parse("""{"console": {"region": "NTSC-J"}, "display": {"aspectRatio": "Auto (Game Native)"}}""")
        val settings = SettingsJson.decode(stored)
        assertEquals("auto", settings.console.region)
        assertEquals("4:3", settings.display.aspectRatio)
    }

    @Test
    fun unreadableFileIsDefaults() {
        assertEquals(EmulatorSettings(), SettingsJson.decode(SettingsJson.parse("not json")))
    }

    @Test
    fun overridesRecordEveryEditedValue() {
        val global = EmulatorSettings()
        val edited = global.copy(display = global.display.copy(hBlend = true))
        val overrides = SettingsOverrides().record(global, edited)
        assertEquals(setOf("display.hBlend"), overrides.paths)
        assertEquals(edited, SettingsJson.merge(global, overrides))

        // An override equal to the global value stays: the player chose it for this game.
        val back = overrides.record(edited, global)
        assertEquals(setOf("display.hBlend"), back.paths)

        // A later global change reaches only the values the game does not override.
        val newGlobal = global.copy(display = global.display.copy(hBlend = true, lineStart = 8))
        val merged = SettingsJson.merge(newGlobal, back)
        assertEquals(false, merged.display.hBlend)
        assertEquals(8, merged.display.lineStart)

        assertTrue(back.without(listOf("display.hBlend")).isEmpty())
    }

    @Test
    fun keysCarryTheirFields() {
        val keys = CoreKeys.of(EmulatorSettings()).associateBy { it.id }
        assertEquals(setOf("console.cart"), keys.getValue("Core/ss.cart").fields)
        assertEquals("auto", keys.getValue("Core/ss.cart").value)
        assertEquals("4:3", keys.getValue("Display/AspectRatio").value)
        assertEquals("%f.%M%x", keys.getValue("Core/filesys.fname_sav").value)
        // No key is written twice, and none clashes with a pinned one.
        assertEquals(keys.size, CoreKeys.of(EmulatorSettings()).size)
        assertTrue(CoreKeys.pinned.none { it.id in keys })
    }

    @Test
    fun regionAndLinesAreComputed() {
        val auto = CoreKeys.of(EmulatorSettings()).associateBy { it.id }
        assertEquals("1", auto.getValue("Core/ss.region_autodetect").value)
        assertEquals("jp", auto.getValue("Core/ss.region_default").value)

        val keys = CoreKeys.of(
            EmulatorSettings(
                console = ConsoleSettings(region = "eu"),
                display = DisplaySettings(lineStart = 20, lineEnd = 10),
            ),
        ).associateBy { it.id }
        assertEquals("0", keys.getValue("Core/ss.region_autodetect").value)
        assertEquals("eu", keys.getValue("Core/ss.region_default").value)
        assertEquals("20", keys.getValue("Core/ss.slstart").value)
        assertEquals("20", keys.getValue("Core/ss.slend").value)
    }

    @Test
    fun sharedBackupMemoryNamesTheFile() {
        val keys = CoreKeys.of(
            EmulatorSettings(system = SystemSettings(backupRam = "Shared", backupRamFile = "100%.bkr")),
        ).associateBy { it.id }
        assertEquals("100%%.%x", keys.getValue("Core/filesys.fname_sav").value)
    }

    @Test
    fun gameBiosGoesToEveryRegion() {
        val keys = CoreKeys.of(EmulatorSettings(system = SystemSettings(biosFilename = "sega_101.bin"))).associateBy { it.id }
        for (key in listOf("BIOS/PathNTSCJ", "BIOS/PathNTSCU", "BIOS/PathPAL")) assertEquals("sega_101.bin", keys.getValue(key).value)
    }

    @Test
    fun copyPathsKeepsOnlyThosePaths() {
        val boot = EmulatorSettings(console = ConsoleSettings(region = "jp"))
        val now = EmulatorSettings(console = ConsoleSettings(region = "na"), audio = AudioSettings(volume = 50))
        val held = SettingsJson.copyPaths(now, boot, SettingsEffects.appliesOnNextBoot)
        assertEquals("jp", held.console.region)
        assertEquals(50, held.audio.volume)
    }

    @Test
    fun nextBootPathsExist() {
        val all = leafPaths(SettingsJson.encode(EmulatorSettings()))
        for (path in SettingsEffects.appliesOnNextBoot) assertTrue(path, path in all)
    }

    @Test
    fun padTypeFallsBackOnAnUnknownValue() {
        val stored = SettingsJson.parse("""{"console": {"padType": "3d"}}""")
        assertEquals("3d", SettingsJson.decode(stored).console.padType)
        val bad = SettingsJson.parse("""{"console": {"padType": "analog"}}""")
        assertEquals("standard", SettingsJson.decode(bad).console.padType)
    }

    @Test
    fun padTypeReachesTheCoreNotARunningGame() {
        // Sent as Input/PadType before the boot; under a running game it keeps what it booted with.
        val keys = CoreKeys.of(EmulatorSettings()).associateBy { it.id }
        assertEquals("standard", keys.getValue("Input/PadType").value)
        assertEquals("Input/PadType", keys.getValue("Input/PadType").id)

        val keys3d = CoreKeys.of(EmulatorSettings(console = ConsoleSettings(padType = "3d"))).associateBy { it.id }
        assertEquals(setOf("console.padType"), keys3d.getValue("Input/PadType").fields)
        assertTrue("console.padType" in SettingsEffects.appliesOnNextBoot)
    }

    private fun leafPaths(obj: kotlinx.serialization.json.JsonObject, prefix: String = ""): Set<String> = buildSet {
        for ((key, value) in obj) {
            if (value is kotlinx.serialization.json.JsonObject) addAll(leafPaths(value, "$prefix$key."))
            else add(prefix + key)
        }
    }
}
