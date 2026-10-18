package com.kira3.ametrine.ui.settings

import android.view.KeyEvent
import com.kira3.ametrine.R
import com.kira3.ametrine.ui.settings.Label.Companion.res
import com.kira3.ametrine.ui.settings.Label.Companion.text

/**
 * What each settings category shows. Items are the core's settings (Mednafen's, through
 * CoreKeys) and the app's own; option values are the stored values, which for the core's
 * settings are Mednafen's own names ("extram4", "weave").
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

    private fun graphics() = listOf(
        SettingsSection(
            R.string.settings_section_screen,
            listOf(
                Choice(
                    "display.aspectRatio", R.string.settings_aspect_ratio,
                    options = named(
                        "4:3" to text("4:3"), "16:9" to text("16:9"),
                        "Stretch" to res(R.string.settings_opt_stretch),
                    ),
                    inline = true,
                ),
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
            R.string.settings_section_picture,
            listOf(
                Toggle("display.hOverscan", R.string.settings_h_overscan, R.string.settings_h_overscan_summary),
                Toggle("display.hBlend", R.string.settings_h_blend, R.string.settings_h_blend_summary),
                Choice(
                    "display.deinterlacer", R.string.settings_deinterlacing, R.string.settings_deinterlacing_summary,
                    named(
                        "weave" to res(R.string.settings_opt_deinterlace_weave),
                        "bob" to res(R.string.settings_opt_deinterlace_bob),
                        "bob_offset" to res(R.string.settings_opt_deinterlace_bob_offset),
                        "blend" to res(R.string.settings_opt_deinterlace_blend),
                    ),
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_display,
            listOf(
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
                Toggle("frameLimit.screenshotButton", R.string.settings_screenshot_button, R.string.settings_screenshot_button_summary),
                Toggle("frameLimit.macroButton", R.string.settings_macro_button, R.string.settings_macro_button_summary),
            ),
        ),
    )

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
                Choice(
                    "frameLimit.fastForwardSpeed", R.string.menu_fast_forward_speed, R.string.settings_ff_speed_summary,
                    listOf(Option(0, res(R.string.menu_fast_forward_unlimited))) +
                        listOf(2, 3, 4, 6, 8, 10).map { Option(it, text("$it×")) },
                ),
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

    private fun audio() = listOf(
        SettingsSection(
            R.string.settings_section_volume,
            listOf(
                Slider("audio.volume", R.string.settings_volume, range = 0..200, step = 5, unit = R.string.settings_unit_percent),
                Slider("audio.fastForwardVolume", R.string.settings_ff_volume, range = 0..200, step = 5, unit = R.string.settings_unit_percent),
                Toggle("audio.muted", R.string.settings_mute),
            ),
        ),
        SettingsSection(
            R.string.settings_section_audio_output,
            listOf(
                Slider("audio.bufferMs", R.string.settings_audio_buffer, R.string.settings_audio_buffer_summary, range = 20..300, step = 10, unit = R.string.settings_unit_ms),
                Slider("audio.resampleQuality", R.string.settings_resample_quality, R.string.settings_resample_quality_summary, range = 0..10),
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

    private fun system() = listOf(
        SettingsSection(
            R.string.settings_section_boot,
            listOf(
                Choice(
                    "console.region", R.string.settings_region, R.string.settings_region_summary,
                    named(
                        "auto" to auto, "jp" to res(R.string.settings_opt_region_japan),
                        "na" to res(R.string.settings_opt_region_na), "eu" to res(R.string.settings_opt_region_europe),
                    ),
                    inline = true,
                ),
                BiosManageSetting,
                GameBiosSetting,
                Choice(
                    "console.language", R.string.settings_language, R.string.settings_language_summary,
                    named(
                        "japanese" to text("日本語"), "english" to text("English"), "german" to text("Deutsch"),
                        "french" to text("Français"), "spanish" to text("Español"), "italian" to text("Italiano"),
                    ),
                ),
                Toggle("console.autoRtc", R.string.settings_auto_rtc, R.string.settings_auto_rtc_summary),
            ),
        ),
        SettingsSection(
            R.string.settings_section_cart,
            listOf(
                Choice(
                    "console.cart", R.string.settings_cart, R.string.settings_cart_summary,
                    named(
                        "auto" to auto,
                        "backup" to res(R.string.settings_opt_cart_backup),
                        "extram1" to res(R.string.settings_opt_cart_extram1),
                        "extram4" to res(R.string.settings_opt_cart_extram4),
                        "none" to res(R.string.settings_opt_cart_none),
                    ),
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_backup_ram,
            listOf(
                Choice(
                    "system.backupRam", R.string.settings_backup_ram, R.string.settings_backup_ram_summary,
                    named(
                        "PerGame" to res(R.string.settings_opt_backup_per_game),
                        "Shared" to res(R.string.settings_opt_backup_shared),
                    ),
                    inline = true,
                ),
                MemoryCardSlotSetting("system.backupRamFile", R.string.settings_backup_ram_file) { it.string("system.backupRam") == "Shared" },
                MemoryCardsManageSetting,
            ),
        ),
    )

    private fun advanced() = listOf(
        SettingsSection(
            R.string.settings_section_lines,
            listOf(
                Slider("display.lineStart", R.string.settings_line_start, R.string.settings_line_start_summary, range = 0..239),
                Slider("display.lineEnd", R.string.settings_line_end, range = 0..239),
                Slider("display.lineStartPal", R.string.settings_line_start_pal, range = -16..271),
                Slider("display.lineEndPal", R.string.settings_line_end_pal, range = -16..271),
                ResetSetting(
                    R.string.settings_lines_reset, R.string.settings_lines_reset_summary,
                    listOf("display.lineStart" to 0, "display.lineEnd" to 239, "display.lineStartPal" to 0, "display.lineEndPal" to 255),
                ),
            ),
        ),
    )
}
