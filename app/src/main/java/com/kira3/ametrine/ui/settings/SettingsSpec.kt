package com.kira3.ametrine.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kira3.ametrine.R
import com.kira3.ametrine.emucore.settings.SettingsEffects
import com.kira3.ametrine.emucore.settings.SettingsValues

// The settings screens are described as data: each category is a list of sections, each
// section a list of settings addressed by their dotted path in EmulatorSettings. The rows
// are drawn from this in SettingsRows.kt.

enum class SettingsCategory(
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int,
) {
    Graphics(R.string.settings_graphics, R.string.settings_graphics_summary, R.drawable.ic_palette),
    Performance(R.string.settings_performance, R.string.settings_performance_summary, R.drawable.ic_speed),
    Audio(R.string.settings_audio, R.string.settings_audio_summary, R.drawable.ic_volume_up),
    Controls(R.string.settings_controls, R.string.settings_controls_summary, R.drawable.ic_gamepad),
    System(R.string.settings_system, R.string.settings_system_summary, R.drawable.ic_memory),
    Advanced(R.string.settings_advanced, R.string.settings_advanced_summary, R.drawable.ic_build),
    Miscellaneous(R.string.settings_misc, R.string.settings_misc_summary, R.drawable.ic_tune);

    val sections: List<SettingsSection> get() = SettingsCatalog.sections(this)

    /** Every path this category edits, for counting and resetting a game's overrides. */
    val paths: Set<String> by lazy { sections.flatMap { s -> s.settings.mapNotNull { it.path } }.toSet() }
}

/** [advanced] sections stay folded away until the user asks for them. */
class SettingsSection(
    @StringRes val title: Int,
    val settings: List<Setting>,
    val advanced: Boolean = false,
)

/** Text for a value: a string resource, or text that needs no translation ("16:9"). */
class Label private constructor(@StringRes private val res: Int, private val text: String?) {
    @Composable
    fun resolve(): String = text ?: stringResource(res)

    companion object {
        fun res(@StringRes res: Int) = Label(res, null)
        fun text(text: String) = Label(0, text)
    }
}

class Option(val value: Any, val label: Label)

sealed class Setting(
    /** Dotted path of the value this row edits, or null for a row that edits several. */
    val path: String?,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    /** Hides the row unless this is true for the current values. */
    val visibleWhen: ((SettingsValues) -> Boolean)?,
    /** Disables the row unless this is true for the current values. */
    val enabledWhen: ((SettingsValues) -> Boolean)?,
    /** Shown only in a game's settings. */
    val gameOnly: Boolean = false,
    /** Shown only in the global settings. */
    val globalOnly: Boolean = false,
) {
    val appliesOnNextBoot: Boolean get() = path in SettingsEffects.appliesOnNextBoot
}

/** A switch. [on] and [off] are the stored values, for settings that are not Booleans. */
class Toggle(
    path: String,
    @StringRes title: Int,
    @StringRes summary: Int = 0,
    val on: Any = true,
    val off: Any = false,
    visibleWhen: ((SettingsValues) -> Boolean)? = null,
    enabledWhen: ((SettingsValues) -> Boolean)? = null,
) : Setting(path, title, summary, visibleWhen, enabledWhen)

/** One of [options]: as connected buttons in the row when [inline], else in a dialog. */
class Choice(
    path: String,
    @StringRes title: Int,
    @StringRes summary: Int = 0,
    val options: List<Option>,
    val inline: Boolean = false,
    visibleWhen: ((SettingsValues) -> Boolean)? = null,
    enabledWhen: ((SettingsValues) -> Boolean)? = null,
    gameOnly: Boolean = false,
) : Setting(path, title, summary, visibleWhen, enabledWhen, gameOnly)

/** A whole number on a slider. [labels] replace the plain number for some values; [unit] formats the rest. */
class Slider(
    path: String,
    @StringRes title: Int,
    @StringRes summary: Int = 0,
    val range: IntRange,
    val step: Int = 1,
    @StringRes val unit: Int = 0,
    val labels: Map<Int, Label> = emptyMap(),
    visibleWhen: ((SettingsValues) -> Boolean)? = null,
    enabledWhen: ((SettingsValues) -> Boolean)? = null,
) : Setting(path, title, summary, visibleWhen, enabledWhen)

/** A number typed into a dialog. [decimal] allows fractions (Float settings). */
class NumberInput(
    path: String,
    @StringRes title: Int,
    @StringRes summary: Int = 0,
    val min: Double,
    val max: Double,
    val decimal: Boolean = false,
    visibleWhen: ((SettingsValues) -> Boolean)? = null,
    enabledWhen: ((SettingsValues) -> Boolean)? = null,
) : Setting(path, title, summary, visibleWhen, enabledWhen)

/** Text typed into a dialog. [multiline] is for long text such as a prompt. */
class TextInput(
    path: String,
    @StringRes title: Int,
    @StringRes summary: Int = 0,
    val multiline: Boolean = false,
    val allowEmpty: Boolean = true,
    visibleWhen: ((SettingsValues) -> Boolean)? = null,
    enabledWhen: ((SettingsValues) -> Boolean)? = null,
) : Setting(path, title, summary, visibleWhen, enabledWhen)

/** The Gemini API key, typed in a dialog and kept apart from the settings files. */
data object GeminiApiKeySetting : Setting(
    null, R.string.settings_gemini_key, R.string.settings_gemini_key_summary, null,
    enabledWhen = { it.boolean("gemini.enabled") }, globalOnly = true,
)

/** The Gemini model, chosen from the models the API key can use, fetched when the dialog opens. */
data object GeminiModelSetting : Setting(
    "gemini.model", R.string.settings_gemini_model, R.string.settings_gemini_model_summary, null,
    enabledWhen = { it.boolean("gemini.enabled") },
)

/** The BIOS a game boots with, for every region; the options are the installed BIOS files. */
data object GameBiosSetting : Setting(
    "system.biosFilename", R.string.settings_game_bios, R.string.settings_game_bios_summary, null, null, gameOnly = true,
)

/** The custom Vulkan driver, picked on its own screen where packs are also installed. */
data object GpuDriverSetting : Setting(
    "output.customDriverId", R.string.settings_gpu_driver, R.string.settings_gpu_driver_summary, null,
    null,
)

/** The BIOS files the app holds, managed on their own screen. */
data object BiosManageSetting : Setting(
    null, R.string.settings_bios_manage, R.string.settings_bios_manage_summary, null, null, globalOnly = true,
)

/** The controllers connected now, shown for information. */
data object ControllersSetting : Setting(
    null, R.string.settings_controllers, 0, null, null, globalOnly = true,
)

/** The key of a physical controller that presses one pad button, learned by pressing it in a dialog. */
class KeyAssignSetting(
    path: String,
    @StringRes title: Int,
    /** The key this button has when nothing is changed. */
    val default: Int,
) : Setting(path, title, 0, null, null)

/** The touch pad's layout, edited on the play screen's own editor (for a game's settings: that game's layout). */
data object PadLayoutSetting : Setting(
    null, R.string.settings_pad_layout, R.string.settings_pad_layout_summary, null, null,
)

/** Puts the settings at [defaults] (path to default value) back to those values (in a game: back to the global ones). */
class ResetSetting(
    @StringRes title: Int,
    @StringRes summary: Int,
    val defaults: List<Pair<String, Any>>,
) : Setting(null, title, summary, null, null)

/** A backup memory file, chosen from the files the app holds (MemoryCardRepository). */
class MemoryCardSlotSetting(
    path: String,
    @StringRes title: Int,
    enabledWhen: ((SettingsValues) -> Boolean)? = null,
) : Setting(path, title, 0, null, enabledWhen)

/** The backup memory files the app holds, managed on their own screen. */
data object MemoryCardsManageSetting : Setting(
    null, R.string.settings_memory_cards_manage, R.string.settings_memory_cards_manage_summary, null, null, globalOnly = true,
)
