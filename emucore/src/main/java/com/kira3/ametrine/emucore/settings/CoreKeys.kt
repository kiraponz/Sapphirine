package com.kira3.ametrine.emucore.settings

import kotlin.reflect.KProperty0

/** One value of the core's settings: a Mednafen setting ("Core"/its name) or one of the host's own. */
internal data class CoreKey(
    val section: String,
    val key: String,
    val value: String,
    /** Dotted paths of the [EmulatorSettings] fields this key is computed from. */
    val fields: Set<String>,
) {
    val id: String get() = "$section/$key"
}

/**
 * Maps [EmulatorSettings] to the core's settings keys (NativeCore.setSetting, which
 * android_host.cpp reads). The single place that knows key names. Section "Core" holds
 * Mednafen's own settings by name; "BIOS", "Display", "Main" and "Audio" are the host's.
 */
internal object CoreKeys {
    fun of(s: EmulatorSettings): List<CoreKey> = KeyWriter().apply { write(s) }.keys

    /** Mednafen settings the app fixes whatever the settings say. Written on every apply. */
    val pinned: List<CoreKey> = KeyWriter().apply {
        // No sound (or anything else) goes to standard output.
        put("Core", "ss.midi", "none")
        // The app connects one pad; a multitap waits for the controller settings.
        put("Core", "ss.input.sport1.multitap", "0")
        put("Core", "ss.input.sport2.multitap", "0")
        // The VDP2 rendering thread stays where the scheduler puts it.
        put("Core", "ss.affinity.vdp2", "0")
        put("Core", "affinity.cd", "0")
        // Games not in the cart database get the backup memory cart, as Mednafen does.
        put("Core", "ss.cart.auto_default", "backup")
        // The aspect ratio is the app's (Display/AspectRatio); the core sizes lines to match.
        put("Core", "ss.correct_aspect", "1")
    }.keys

    private class KeyWriter {
        val keys = ArrayList<CoreKey>()

        fun put(section: String, key: String, value: String, fields: Set<String> = emptySet()) {
            keys += CoreKey(section, key, value, fields)
        }

        fun group(name: String) = Group(name)

        inner class Group(private val name: String) {
            fun path(property: KProperty0<*>) = "$name.${property.name}"

            fun bool(property: KProperty0<Boolean>, section: String, key: String) =
                put(section, key, property.get().toString(), setOf(path(property)))

            fun int(property: KProperty0<Int>, section: String, key: String, range: IntRange? = null) {
                val value = property.get().let { if (range != null) it.coerceIn(range) else it }
                put(section, key, value.toString(), setOf(path(property)))
            }

            fun string(property: KProperty0<String>, section: String, key: String) =
                put(section, key, property.get(), setOf(path(property)))
        }

        fun write(s: EmulatorSettings) {
            writeConsole(s.console)
            writeDisplay(s.display)
            writeFrameLimit(s.frameLimit)
            writeAudio(s.audio)
            writeSystem(s.system)
            writeRewind(s.rewind)
        }

        private fun writeConsole(c: ConsoleSettings) = with(c) {
            val g = group(EmulatorSettings::console.name)
            val auto = region == "auto"
            put("Core", "ss.region_autodetect", if (auto) "1" else "0", setOf(g.path(::region)))
            // A disc that names no region the console knows boots as a Japanese one.
            put("Core", "ss.region_default", if (auto) "jp" else region, setOf(g.path(::region)))
            g.string(::cart, "Core", "ss.cart")
            put("Core", "ss.smpc.autortc", if (autoRtc) "1" else "0", setOf(g.path(::autoRtc)))
            g.string(::language, "Core", "ss.smpc.autortc.lang")
        }

        private fun writeDisplay(d: DisplaySettings) = with(d) {
            val g = group(EmulatorSettings::display.name)
            g.string(::aspectRatio, "Display", "AspectRatio")
            g.string(::deinterlacer, "Core", "video.deinterlacer")
            put("Core", "ss.h_overscan", if (hOverscan) "1" else "0", setOf(g.path(::hOverscan)))
            put("Core", "ss.h_blend", if (hBlend) "1" else "0", setOf(g.path(::hBlend)))
            // The core takes the lines in order: a last line above the first shows the first alone.
            val ntsc = setOf(g.path(::lineStart), g.path(::lineEnd))
            val start = lineStart.coerceIn(0, 239)
            put("Core", "ss.slstart", start.toString(), ntsc)
            put("Core", "ss.slend", lineEnd.coerceIn(start, 239).toString(), ntsc)
            val pal = setOf(g.path(::lineStartPal), g.path(::lineEndPal))
            val startPal = lineStartPal.coerceIn(-16, 271)
            put("Core", "ss.slstartp", startPal.toString(), pal)
            put("Core", "ss.slendp", lineEndPal.coerceIn(startPal, 271).toString(), pal)
        }

        private fun writeFrameLimit(f: FrameLimitSettings) = with(f) {
            val g = group(EmulatorSettings::frameLimit.name)
            g.int(::fastForwardSpeed, "Main", "FastForwardSpeed", 0..10)
        }

        private fun writeAudio(a: AudioSettings) = with(a) {
            val g = group(EmulatorSettings::audio.name)
            g.int(::volume, "Audio", "OutputVolume", 0..200)
            g.int(::fastForwardVolume, "Audio", "FastForwardVolume", 0..200)
            g.bool(::muted, "Audio", "OutputMuted")
            g.int(::bufferMs, "Audio", "BufferMS", 10..500)
            g.int(::resampleQuality, "Core", "ss.scsp.resamp_quality", 0..10)
        }

        private fun writeSystem(s: SystemSettings) = with(s) {
            val g = group(EmulatorSettings::system.name)
            // The game's own BIOS for every region; otherwise the core picks one for the region from the BIOS folder.
            for (key in listOf("PathNTSCJ", "PathNTSCU", "PathPAL")) put("BIOS", key, biosFilename, setOf(g.path(::biosFilename)))
            // Mednafen names a game's files "<disc file>.<md5>.<ext>" (.bkr the console's backup
            // memory, .bcr a backup cart's, .smpc the clock); shared, every game uses the file's base
            // name instead. A % in it would read as a format code, so it is doubled.
            val shared = backupRam == "Shared"
            val base = backupRamFile.substringBeforeLast('.').ifEmpty { "shared" }.replace("%", "%%")
            put("Core", "filesys.fname_sav", if (shared) "$base.%x" else "%f.%M%x", setOf(g.path(::backupRam), g.path(::backupRamFile)))
        }

        private fun writeRewind(r: RewindSettings) = with(r) {
            val g = group(EmulatorSettings::rewind.name)
            g.bool(::enabled, "Main", "RewindEnable")
            put("Main", "RewindFrequency", intervalSeconds.coerceIn(1, 60).toFloat().toString(), setOf(g.path(::intervalSeconds)))
            g.int(::keep, "Main", "RewindSaveSlots", 1..20)
        }
    }
}
