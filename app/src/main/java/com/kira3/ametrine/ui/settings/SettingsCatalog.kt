package com.kira3.ametrine.ui.settings

import android.view.KeyEvent
import com.kira3.ametrine.R
import com.kira3.ametrine.ui.settings.Label.Companion.res
import com.kira3.ametrine.ui.settings.Label.Companion.text

/**
 * What each settings category shows. Items are the core's settings (docs/CORE_SETTINGS.md)
 * and the app's own; option values are the stored values, which for the core's settings are
 * its own names ("Nearest", "Recompiler").
 */
object SettingsCatalog {
    private val byCategory: Map<SettingsCategory, List<SettingsSection>> by lazy {
        mapOf(
            SettingsCategory.Graphics to graphics(),
            SettingsCategory.Performance to performance(),
            SettingsCategory.Audio to audio(),
            SettingsCategory.Controls to controls(),
            SettingsCategory.System to system(),
            SettingsCategory.Advanced to advanced(),
            SettingsCategory.Miscellaneous to miscellaneous(),
        )
    }

    fun sections(category: SettingsCategory): List<SettingsSection> = byCategory.getValue(category)

    private fun options(vararg labels: Label): List<Option> = labels.mapIndexed { index, label -> Option(index, label) }

    private val off = res(R.string.settings_opt_off)
    private val auto = res(R.string.settings_opt_auto)

    /** Options whose stored values are the core's names. */
    private fun named(vararg pairs: Pair<String, Label>): List<Option> = pairs.map { (value, label) -> Option(value, label) }

    private val speedups = listOf(
        Option(1, res(R.string.settings_opt_normal)), Option(2, text("2×")), Option(3, text("3×")), Option(4, text("4×")),
        Option(6, text("6×")), Option(8, text("8×")), Option(10, text("10×")), Option(0, res(R.string.settings_opt_max)),
    )

    private val textureFilters = named(
        "Nearest" to res(R.string.settings_opt_nearest),
        "Bilinear" to res(R.string.settings_opt_bilinear),
        "BilinearBinAlpha" to res(R.string.settings_opt_bilinear_bin_alpha),
        "JINC2" to text("JINC2"),
        "JINC2BinAlpha" to res(R.string.settings_opt_jinc2_bin_alpha),
        "xBR" to text("xBR"),
        "xBRBinAlpha" to res(R.string.settings_opt_xbr_bin_alpha),
    )

    private fun graphics() = listOf(
        SettingsSection(
            R.string.settings_section_rendering,
            listOf(
                Choice(
                    "gpu.renderer", R.string.settings_renderer, R.string.settings_renderer_summary,
                    named(
                        "auto" to auto, "opengl" to text("OpenGL"),
                        "vulkan" to text("Vulkan"), "software" to res(R.string.settings_opt_software),
                    ),
                    inline = true,
                ),
                // No custom Vulkan driver (GpuDriverSetting, the Turnip UBWC hint): the PS1's
                // drawing is light enough for the system driver, or OpenGL where it falls short.
                Choice(
                    "gpu.resolutionScale", R.string.settings_resolution, R.string.settings_resolution_summary,
                    listOf(
                        Option(0, res(R.string.settings_opt_match_window)),
                        Option(1, res(R.string.settings_opt_native)),
                        Option(2, text("2× (480p)")), Option(3, text("3× (720p)")), Option(4, text("4× (960p)")),
                        Option(5, text("5× (1200p)")), Option(6, text("6× (1440p)")), Option(8, text("8× (1920p)")),
                    ),
                    enabledWhen = { it.string("gpu.renderer") != "software" },
                ),
                Choice(
                    "display.aspectRatio", R.string.settings_aspect_ratio,
                    options = named(
                        "Auto (Game Native)" to res(R.string.settings_opt_aspect_game),
                        "Auto (Match Window)" to res(R.string.settings_opt_stretch),
                        "4:3" to text("4:3"), "16:9" to text("16:9"), "19:9" to text("19:9"), "20:9" to text("20:9"),
                        "PAR 1:1" to res(R.string.settings_opt_par_1_1),
                        "Custom" to res(R.string.settings_opt_custom),
                    ),
                ),
                NumberInput(
                    "display.customAspectNumerator", R.string.settings_custom_aspect_width, min = 1.0, max = 1000.0,
                    visibleWhen = { it.string("display.aspectRatio") == "Custom" },
                ),
                NumberInput(
                    "display.customAspectDenominator", R.string.settings_custom_aspect_height, min = 1.0, max = 1000.0,
                    visibleWhen = { it.string("display.aspectRatio") == "Custom" },
                ),
                Toggle("gpu.widescreenHack", R.string.settings_widescreen_hack, R.string.settings_widescreen_hack_summary),
                Choice(
                    "output.orientation", R.string.settings_orientation, R.string.settings_orientation_summary,
                    options(
                        res(R.string.settings_opt_device), res(R.string.settings_opt_landscape),
                        res(R.string.settings_opt_portrait), res(R.string.settings_opt_auto_rotate),
                    ),
                    inline = true,
                ),
                Toggle("output.portraitRenderTop", R.string.settings_portrait_top),
            ),
        ),
        SettingsSection(
            R.string.settings_section_enhancements,
            listOf(
                Choice("gpu.textureFilter", R.string.settings_texture_filtering, R.string.settings_texture_filtering_summary, textureFilters),
                Toggle("gpu.trueColor", R.string.settings_true_color, R.string.settings_true_color_summary),
                Toggle(
                    "gpu.scaledDithering", R.string.settings_scaled_dithering, R.string.settings_scaled_dithering_summary,
                    enabledWhen = { !it.boolean("gpu.trueColor") },
                ),
                Toggle("gpu.debanding", R.string.settings_debanding, R.string.settings_debanding_summary),
                Choice(
                    "gpu.multisamples", R.string.settings_msaa,
                    options = listOf(Option(1, off), Option(2, text("2×")), Option(4, text("4×")), Option(8, text("8×"))),
                    inline = true,
                ),
                Toggle(
                    "gpu.perSampleShading", R.string.settings_ssaa, R.string.settings_ssaa_summary,
                    enabledWhen = { it.int("gpu.multisamples") > 1 },
                ),
                Toggle("gpu.chromaSmoothing24Bit", R.string.settings_chroma_smoothing, R.string.settings_chroma_smoothing_summary),
            ),
        ),
        SettingsSection(
            R.string.settings_section_pgxp,
            listOf(
                Toggle("gpu.pgxpEnable", R.string.settings_pgxp, R.string.settings_pgxp_summary),
                pgxpToggle("gpu.pgxpCulling", R.string.settings_pgxp_culling),
                pgxpToggle("gpu.pgxpTextureCorrection", R.string.settings_pgxp_texture_correction),
                pgxpToggle("gpu.pgxpColorCorrection", R.string.settings_pgxp_color_correction),
                pgxpToggle("gpu.pgxpVertexCache", R.string.settings_pgxp_vertex_cache, R.string.settings_pgxp_vertex_cache_summary),
                pgxpToggle("gpu.pgxpCpu", R.string.settings_pgxp_cpu, R.string.settings_pgxp_cpu_summary),
                pgxpToggle("gpu.pgxpPreserveProjFp", R.string.settings_pgxp_preserve_proj),
                pgxpToggle("gpu.pgxpDepthBuffer", R.string.settings_pgxp_depth_buffer, R.string.settings_pgxp_depth_buffer_summary),
            ),
        ),
        SettingsSection(
            R.string.settings_section_display,
            listOf(
                Choice(
                    "display.cropMode", R.string.settings_crop_mode,
                    options = named(
                        "None" to res(R.string.settings_opt_crop_none),
                        "Overscan" to res(R.string.settings_opt_crop_overscan),
                        "Borders" to res(R.string.settings_opt_crop_borders),
                    ),
                    inline = true,
                ),
                Choice(
                    "display.deinterlacingMode", R.string.settings_deinterlacing,
                    options = named(
                        "Disabled" to res(R.string.settings_opt_deinterlace_off),
                        "Weave" to res(R.string.settings_opt_deinterlace_weave),
                        "Blend" to res(R.string.settings_opt_deinterlace_blend),
                        "Adaptive" to res(R.string.settings_opt_deinterlace_adaptive),
                    ),
                ),
                Choice(
                    "display.scaling", R.string.settings_display_filter, R.string.settings_display_filter_summary,
                    named(
                        "Nearest" to res(R.string.settings_opt_nearest),
                        "NearestInteger" to res(R.string.settings_opt_nearest_integer),
                        "BilinearSmooth" to res(R.string.settings_opt_smooth),
                        "BilinearSharp" to res(R.string.settings_opt_sharp),
                        "BilinearInteger" to res(R.string.settings_opt_bilinear_integer),
                    ),
                ),
                Toggle("display.force43For24Bit", R.string.settings_force_43_movies, R.string.settings_force_43_movies_summary),
                Choice(
                    "output.hwScaler", R.string.settings_display_resolution, R.string.settings_display_resolution_summary,
                    listOf(
                        Option(0, res(R.string.settings_opt_screen)), Option(3, text("1440p")),
                        Option(2, text("960p")), Option(1, text("480p")),
                    ),
                    inline = true,
                ),
                Choice(
                    "output.screenResOverride", R.string.settings_screen_res_override, R.string.settings_screen_res_override_summary,
                    listOf(
                        Option("auto", auto), Option("2560x1440", text("1440p")),
                        Option("1920x1080", text("1080p")), Option("1280x720", text("720p")),
                    ),
                    inline = true,
                ),
            ),
            advanced = true,
        ),
        SettingsSection(
            R.string.settings_section_overlay,
            listOf(
                Toggle("osd.showFps", R.string.settings_osd_fps),
                Toggle("osd.showSpeed", R.string.settings_osd_speed),
                Toggle("osd.showResolution", R.string.settings_osd_resolution),
                Toggle("osd.showCpu", R.string.settings_osd_cpu),
                Toggle("osd.showGpu", R.string.settings_osd_gpu),
                Toggle("osd.showFrameTimes", R.string.settings_osd_frame_times),
                Toggle("osd.showGpuStats", R.string.settings_osd_gpu_stats),
                Toggle("osd.showInputs", R.string.settings_osd_inputs),
                Toggle("osd.showEnhancements", R.string.settings_osd_enhancements),
                Toggle("osd.showStatusIndicators", R.string.settings_osd_status_indicators),
                Toggle("frameLimit.screenshotButton", R.string.settings_screenshot_button, R.string.settings_screenshot_button_summary),
                Toggle("frameLimit.macroButton", R.string.settings_macro_button, R.string.settings_macro_button_summary),
                Toggle("osd.showMessages", R.string.settings_osd_messages, R.string.settings_osd_messages_summary),
                Slider("osd.scale", R.string.settings_osd_scale, range = 25..200, step = 5, unit = R.string.settings_unit_percent),
            ),
            advanced = true,
        ),
    )

    private fun pgxpToggle(path: String, title: Int, summary: Int = 0) =
        Toggle(path, title, summary, enabledWhen = { it.boolean("gpu.pgxpEnable") })

    private fun miscellaneous() = listOf(
        SettingsSection(
            R.string.settings_section_gemini,
            listOf(
                Toggle("gemini.enabled", R.string.settings_gemini_enabled, R.string.settings_gemini_enabled_summary),
                GeminiApiKeySetting,
                Toggle(
                    "gemini.memosVisible", R.string.settings_gemini_memos, R.string.settings_gemini_memos_summary,
                    enabledWhen = { it.boolean("gemini.enabled") },
                ),
                GeminiModelSetting,
                TextInput(
                    "gemini.systemPrompt", R.string.settings_gemini_prompt, R.string.settings_gemini_prompt_summary,
                    multiline = true, enabledWhen = { it.boolean("gemini.enabled") },
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_memory_editor,
            listOf(
                Toggle(
                    "memoryEditor.enabled", R.string.settings_memory_editor, R.string.settings_memory_editor_summary,
                ),
            ),
        ),
    )

    private fun performance() = listOf(
        SettingsSection(
            R.string.settings_section_speed,
            listOf(
                Slider(
                    "frameLimit.nominalSpeedPercent", R.string.settings_speed_limit, R.string.settings_speed_limit_summary,
                    range = 10..500, step = 10, unit = R.string.settings_unit_percent,
                    enabledWhen = { it.boolean("frameLimit.frameLimitEnable") },
                ),
                Toggle("frameLimit.frameLimitEnable", R.string.settings_frame_limiter, R.string.settings_frame_limiter_summary),
                Slider(
                    "cpu.overclockPercent", R.string.settings_overclock, R.string.settings_overclock_summary,
                    range = 25..400, step = 25, unit = R.string.settings_unit_percent,
                ),
                Choice("console.cdromReadSpeedup", R.string.settings_cdrom_read_speedup, R.string.settings_cdrom_read_speedup_summary, speedups),
                Choice("console.cdromSeekSpeedup", R.string.settings_cdrom_seek_speedup, options = speedups),
            ),
        ),
        SettingsSection(
            R.string.settings_section_frame_pacing,
            listOf(
                Toggle("display.vsync", R.string.settings_vsync),
                Toggle("display.optimalFramePacing", R.string.settings_optimal_frame_pacing, R.string.settings_optimal_frame_pacing_summary),
                Toggle("display.syncToHostRefreshRate", R.string.settings_sync_host_refresh, R.string.settings_sync_host_refresh_summary),
                Toggle("display.skipDuplicateFrames", R.string.settings_skip_duplicate_frames, R.string.settings_skip_duplicate_frames_summary),
            ),
        ),
        SettingsSection(
            R.string.settings_section_cpu,
            listOf(
                Choice(
                    "cpu.executionMode", R.string.settings_cpu_mode, R.string.settings_cpu_mode_summary,
                    named(
                        "Recompiler" to res(R.string.settings_opt_recompiler),
                        "NewRec" to res(R.string.settings_opt_new_recompiler),
                        "CachedInterpreter" to res(R.string.settings_opt_cached_interpreter),
                        "Interpreter" to res(R.string.settings_opt_interpreter),
                    ),
                ),
                Choice(
                    "cpu.fastmemMode", R.string.settings_fastmem, R.string.settings_fastmem_summary,
                    named("MMap" to res(R.string.settings_opt_fastmem_mmap), "LUT" to res(R.string.settings_opt_fastmem_lut), "Disabled" to off),
                    inline = true,
                ),
                Toggle("cpu.recompilerICache", R.string.settings_icache, R.string.settings_icache_summary),
                Toggle("gpu.useThread", R.string.settings_gpu_thread, R.string.settings_gpu_thread_summary),
                Toggle("gpu.threadedPresentation", R.string.settings_threaded_presentation, R.string.settings_threaded_presentation_summary),
            ),
            advanced = true,
        ),
    )

    private fun audio() = listOf(
        SettingsSection(
            R.string.settings_section_volume,
            listOf(
                Slider("audio.volume", R.string.settings_volume, range = 0..200, step = 5, unit = R.string.settings_unit_percent),
                Slider("audio.fastForwardVolume", R.string.settings_ff_volume, range = 0..200, step = 5, unit = R.string.settings_unit_percent),
                Toggle("audio.muted", R.string.settings_mute),
                Toggle("audio.muteCdAudio", R.string.settings_mute_cd_audio, R.string.settings_mute_cd_audio_summary),
            ),
        ),
        SettingsSection(
            R.string.settings_section_audio_output,
            listOf(
                Choice(
                    "audio.stretchMode", R.string.settings_audio_stretch, R.string.settings_audio_stretch_summary,
                    named(
                        "TimeStretch" to res(R.string.settings_opt_time_stretch),
                        "Resample" to res(R.string.settings_opt_resample),
                        "Off" to off,
                    ),
                    inline = true,
                ),
                Slider("audio.bufferMs", R.string.settings_audio_buffer, range = 20..300, step = 10, unit = R.string.settings_unit_ms),
                Slider(
                    "audio.outputLatencyMs", R.string.settings_output_latency, R.string.settings_output_latency_summary,
                    range = 5..100, step = 5, unit = R.string.settings_unit_ms,
                    enabledWhen = { !it.boolean("audio.outputLatencyMinimal") },
                ),
                Toggle("audio.outputLatencyMinimal", R.string.settings_output_latency_minimal, R.string.settings_output_latency_minimal_summary),
            ),
            advanced = true,
        ),
    )

    private val keyAssignments = listOf(
        KeyAssignSetting("controls.buttonMap.up", R.string.settings_button_up, KeyEvent.KEYCODE_DPAD_UP),
        KeyAssignSetting("controls.buttonMap.down", R.string.settings_button_down, KeyEvent.KEYCODE_DPAD_DOWN),
        KeyAssignSetting("controls.buttonMap.left", R.string.settings_button_left, KeyEvent.KEYCODE_DPAD_LEFT),
        KeyAssignSetting("controls.buttonMap.right", R.string.settings_button_right, KeyEvent.KEYCODE_DPAD_RIGHT),
        KeyAssignSetting("controls.buttonMap.a", R.string.settings_button_a, KeyEvent.KEYCODE_BUTTON_A),
        KeyAssignSetting("controls.buttonMap.b", R.string.settings_button_b, KeyEvent.KEYCODE_BUTTON_B),
        KeyAssignSetting("controls.buttonMap.c", R.string.settings_button_c, KeyEvent.KEYCODE_BUTTON_R1),
        KeyAssignSetting("controls.buttonMap.x", R.string.settings_button_x, KeyEvent.KEYCODE_BUTTON_X),
        KeyAssignSetting("controls.buttonMap.y", R.string.settings_button_y, KeyEvent.KEYCODE_BUTTON_Y),
        KeyAssignSetting("controls.buttonMap.z", R.string.settings_button_z, KeyEvent.KEYCODE_BUTTON_L1),
        KeyAssignSetting("controls.buttonMap.l", R.string.settings_button_l, KeyEvent.KEYCODE_BUTTON_L2),
        KeyAssignSetting("controls.buttonMap.r", R.string.settings_button_r, KeyEvent.KEYCODE_BUTTON_R2),
        KeyAssignSetting("controls.buttonMap.start", R.string.settings_button_start, KeyEvent.KEYCODE_BUTTON_START),
    )

    private fun controls() = listOf(
        SettingsSection(
            R.string.settings_section_pad,
            listOf(
                Choice(
                    "controls.padType", R.string.settings_pad_type, R.string.settings_pad_type_summary,
                    listOf(
                        Option("AnalogController", text("DualShock")),
                        Option("DigitalController", res(R.string.settings_opt_digital_pad)),
                    ),
                    inline = true,
                ),
                Toggle(
                    "controls.forceAnalogOnReset", R.string.settings_force_analog, R.string.settings_force_analog_summary,
                    enabledWhen = { it.string("controls.padType") == "AnalogController" },
                ),
                Toggle(
                    "controls.analogDpadInDigitalMode", R.string.settings_analog_dpad, R.string.settings_analog_dpad_summary,
                    enabledWhen = { it.string("controls.padType") == "AnalogController" },
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_virtual_pad,
            listOf(
                Choice(
                    "controls.stickMode", R.string.settings_stick_mode, R.string.settings_stick_mode_summary,
                    options = options(res(R.string.settings_opt_stick_fixed), res(R.string.settings_opt_stick_floating)),
                ),
                Slider(
                    "controls.padOpacity", R.string.settings_pad_opacity,
                    range = 20..100, step = 5, unit = R.string.settings_unit_percent,
                ),
                Slider(
                    "controls.padIdleSeconds", R.string.settings_pad_idle, R.string.settings_pad_idle_summary,
                    range = 1..31, unit = R.string.settings_unit_seconds, labels = mapOf(31 to off),
                ),
                Toggle("controls.padHaptics", R.string.settings_pad_haptics),
                PadLayoutSetting,
            ),
        ),
        SettingsSection(
            R.string.settings_section_controllers,
            listOf(
                ControllersSetting,
                Slider(
                    "controls.stickDeadzone", R.string.settings_stick_deadzone,
                    range = 0..50, step = 1, unit = R.string.settings_unit_percent,
                ),
                Slider(
                    "controls.triggerThreshold", R.string.settings_trigger_threshold,
                    range = 1..90, step = 1, unit = R.string.settings_unit_percent,
                ),
                ResetSetting(
                    R.string.settings_stick_reset, R.string.settings_stick_reset_summary,
                    listOf("controls.stickDeadzone" to 15, "controls.triggerThreshold" to 8),
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_button_map,
            listOf(
                *keyAssignments.toTypedArray(),
                ResetSetting(
                    R.string.settings_key_reset_all, R.string.settings_key_reset_all_summary,
                    keyAssignments.map { it.path!! to it.default },
                ),
            ),
        ),
    )

    private val memoryCardTypes = named(
        "PerGameTitle" to res(R.string.settings_opt_card_per_game),
        "Shared" to res(R.string.settings_opt_card_shared),
        "None" to res(R.string.settings_opt_card_none),
    )

    private fun system() = listOf(
        SettingsSection(
            R.string.settings_section_boot,
            listOf(
                Toggle("console.fastBoot", R.string.settings_fast_boot, R.string.settings_fast_boot_summary),
                Choice(
                    "console.region", R.string.settings_region, R.string.settings_region_summary,
                    named(
                        "Auto" to auto, "NTSC-J" to res(R.string.settings_opt_region_japan),
                        "NTSC-U" to res(R.string.settings_opt_region_usa), "PAL" to res(R.string.settings_opt_region_europe),
                    ),
                    inline = true,
                ),
                BiosManageSetting,
                GameBiosSetting,
            ),
        ),
        SettingsSection(
            R.string.settings_section_memory_cards,
            listOf(
                Choice("system.memoryCard1Type", R.string.settings_mc_slot1_type, R.string.settings_mc_type_summary, memoryCardTypes, inline = true),
                MemoryCardSlotSetting("system.memoryCard1File", R.string.settings_mc_slot1) { it.string("system.memoryCard1Type") == "Shared" },
                Choice("system.memoryCard2Type", R.string.settings_mc_slot2_type, options = memoryCardTypes, inline = true),
                MemoryCardSlotSetting("system.memoryCard2File", R.string.settings_mc_slot2) { it.string("system.memoryCard2Type") == "Shared" },
                MemoryCardsManageSetting,
            ),
        ),
        SettingsSection(
            R.string.settings_section_rewind,
            listOf(
                Toggle("rewind.enabled", R.string.settings_rewind, R.string.settings_rewind_summary),
                Slider(
                    "rewind.intervalSeconds", R.string.settings_rewind_interval,
                    range = 1..60, unit = R.string.settings_unit_seconds,
                    enabledWhen = { it.boolean("rewind.enabled") },
                ),
                Slider(
                    "rewind.keep", R.string.settings_rewind_keep, R.string.settings_rewind_keep_summary,
                    range = 1..20, enabledWhen = { it.boolean("rewind.enabled") },
                ),
            ),
        ),
    )

    private fun advanced() = listOf(
        SettingsSection(
            R.string.settings_section_console,
            listOf(
                Toggle("console.enable8MbRam", R.string.settings_8mb_ram, R.string.settings_8mb_ram_summary),
                Toggle("console.loadImageToRam", R.string.settings_load_to_ram, R.string.settings_load_to_ram_summary),
            ),
        ),
        SettingsSection(
            R.string.settings_section_gpu_accuracy,
            listOf(
                Toggle("gpu.accurateBlending", R.string.settings_accurate_blending, R.string.settings_accurate_blending_summary),
                Toggle("gpu.disableInterlacing", R.string.settings_disable_interlacing, R.string.settings_disable_interlacing_summary),
                Toggle("gpu.forceNtscTimings", R.string.settings_force_ntsc, R.string.settings_force_ntsc_summary),
                Choice(
                    "gpu.lineDetectMode", R.string.settings_line_detect, R.string.settings_line_detect_summary,
                    named(
                        "Disabled" to off, "Quads" to res(R.string.settings_opt_line_quads),
                        "BasicTriangles" to res(R.string.settings_opt_line_basic_triangles),
                        "AggressiveTriangles" to res(R.string.settings_opt_line_aggressive_triangles),
                    ),
                ),
                Choice(
                    "gpu.downsampleMode", R.string.settings_downsample, R.string.settings_downsample_summary,
                    named("Disabled" to off, "Box" to res(R.string.settings_opt_downsample_box), "Adaptive" to res(R.string.settings_opt_downsample_adaptive)),
                    inline = true,
                ),
            ),
        ),
    )
}
