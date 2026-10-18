package com.kira3.ametrine.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.annotation.StringRes
import com.kira3.ametrine.AmetrineApplication
import com.kira3.ametrine.data.GeminiApi
import com.kira3.ametrine.data.GeminiError
import com.kira3.ametrine.data.GeminiModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kira3.ametrine.R
import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.kira3.ametrine.data.GpuDriver
import com.kira3.ametrine.emucore.BiosRegion
import com.kira3.ametrine.emucore.settings.SettingsValues
import java.util.Locale
import kotlin.math.roundToInt

/** What a row can ask for. Paths are dotted EmulatorSettings paths. */
interface SettingsActions {
    fun set(path: String, value: Any)
    fun reset(path: String)
    fun openGpuDriver()
    fun openBios()
    fun openMemoryCards()
    fun openPadLayout()
}

/** One setting as a segmented list item. [shapes] places it within its section's group. */
@Composable
fun SettingRow(
    setting: Setting,
    state: SettingsUiState,
    isGame: Boolean,
    shapes: ListItemShapes,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val values = state.values
    val enabled = setting.enabledWhen?.invoke(values) != false
    val path = setting.path
    val overline: (@Composable () -> Unit)? =
        if (isGame && path != null && path in state.overrides) {
            { OverrideBadge { actions.reset(path) } }
        } else {
            null
        }
    val row = RowParams(setting, values, enabled, shapes, overline, modifier)
    when (setting) {
        is Toggle -> ToggleRow(row, setting, actions)
        is Choice -> if (setting.inline) InlineChoiceRow(row, setting, actions) else DialogChoiceRow(row, setting, actions)
        is Slider -> SliderRow(row, setting, actions)
        is NumberInput -> NumberRow(row, setting, actions)
        is TextInput -> TextRow(row, setting, actions)
        GeminiApiKeySetting -> GeminiKeyRow(row)
        GeminiModelSetting -> GeminiModelRow(row, actions)
        GameBiosSetting -> DialogChoiceRow(row, biosChoice(state), actions)
        GpuDriverSetting -> GpuDriverRow(row, state, actions)
        BiosManageSetting -> BiosManageRow(row, actions)
        ControllersSetting -> ControllersRow(row)
        is KeyAssignSetting -> KeyAssignRow(row, setting, actions)
        PadLayoutSetting -> PadLayoutRow(row, actions)
        is ResetSetting -> ResetRow(row, setting, state, isGame, actions)
        MemoryCardsManageSetting -> MemoryCardsManageRow(row, actions)
        is MemoryCardSlotSetting -> DialogChoiceRow(row, cardChoice(setting, state, values), actions)
    }
}

private class RowParams(
    val setting: Setting,
    val values: SettingsValues,
    val enabled: Boolean,
    val shapes: ListItemShapes,
    val overline: (@Composable () -> Unit)?,
    val modifier: Modifier,
)

@Composable
private fun biosChoice(state: SettingsUiState): Choice = Choice(
    GameBiosSetting.path!!,
    GameBiosSetting.title,
    GameBiosSetting.summary,
    options = listOf(Option("", Label.res(R.string.settings_bios_default))) +
        state.bioses.map { bios ->
            val known = bios.metadata.region != BiosRegion.Unknown
            Option(bios.fileName, Label.text(if (known) "${bios.metadata.description} (${bios.fileName})" else bios.fileName))
        },
    gameOnly = true,
)

@Composable
private fun cardChoice(setting: MemoryCardSlotSetting, state: SettingsUiState, values: SettingsValues): Choice {
    val current = values.string(setting.path!!)
    // The file system ignores case, so a stored "shared.bkr" is the card "Shared.bkr": keep the stored
    // spelling as the value so that card shows as selected.
    val options = state.cards.map { card ->
        Option(if (card.name.equals(current, ignoreCase = true)) current else card.name, Label.text(card.label))
    }
    // The card in use is offered even if its file does not exist yet: the core creates it on first use.
    val all = if (current.isEmpty() || options.any { it.value == current }) options else options + Option(current, Label.text(current))
    return Choice(
        setting.path, setting.title, 0,
        options = all,
        visibleWhen = setting.visibleWhen, enabledWhen = setting.enabledWhen,
    )
}

@Composable
private fun MemoryCardsManageRow(row: RowParams, actions: SettingsActions) {
    SegmentedListItem(
        onClick = actions::openMemoryCards,
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        supportingContent = { Supporting(MemoryCardsManageSetting) },
    ) { Text(stringResource(MemoryCardsManageSetting.title)) }
}

@Composable
private fun ControllersRow(row: RowParams) {
    val context = LocalContext.current
    var names by remember { mutableStateOf(connectedControllerNames()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { names = connectedControllerNames() }
            override fun onInputDeviceRemoved(deviceId: Int) { names = connectedControllerNames() }
            override fun onInputDeviceChanged(deviceId: Int) { names = connectedControllerNames() }
        }
        manager.registerInputDeviceListener(listener, null)
        onDispose { manager.unregisterInputDeviceListener(listener) }
    }
    SegmentedListItem(
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        supportingContent = {
            Column {
                if (names.isEmpty()) Text(stringResource(R.string.settings_controllers_none))
                for (name in names) Text(name)
            }
        },
    ) { Text(stringResource(ControllersSetting.title)) }
}

/** The key's name without its prefix: BUTTON_A, DPAD_UP. */
private fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

@Composable
private fun KeyAssignRow(row: RowParams, setting: KeyAssignSetting, actions: SettingsActions) {
    val path = setting.path!!
    val current = (row.values[path] as? Number)?.toInt() ?: setting.default
    var open by rememberSaveable { mutableStateOf(false) }
    SegmentedListItem(
        onClick = { open = true },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        supportingContent = {
            Supporting(
                setting,
                value = if (current == KeyEvent.KEYCODE_UNKNOWN) stringResource(R.string.settings_key_none) else keyName(current),
                showSummary = false,
            )
        },
    ) { Text(stringResource(setting.title)) }

    if (open) {
        val focus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(setting.title)) },
            text = {
                Text(
                    stringResource(R.string.settings_key_prompt),
                    modifier = Modifier
                        .focusRequester(focus)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            val fromController = native.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                                native.isFromSource(InputDevice.SOURCE_JOYSTICK) || native.isFromSource(InputDevice.SOURCE_DPAD)
                            if (!fromController || native.keyCode == KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                                open = false
                                if (native.keyCode != current) actions.set(path, native.keyCode)
                            }
                            true
                        },
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = { open = false; actions.set(path, setting.default) }) {
                    Text(stringResource(R.string.settings_key_default))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { open = false; actions.set(path, KeyEvent.KEYCODE_UNKNOWN) }) {
                        Text(stringResource(R.string.settings_key_clear))
                    }
                    TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_key_cancel)) }
                }
            },
        )
    }
}

@Composable
private fun PadLayoutRow(row: RowParams, actions: SettingsActions) {
    SegmentedListItem(
        onClick = actions::openPadLayout,
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        supportingContent = { Supporting(PadLayoutSetting) },
    ) { Text(stringResource(PadLayoutSetting.title)) }
}

@Composable
private fun ResetRow(row: RowParams, setting: ResetSetting, state: SettingsUiState, isGame: Boolean, actions: SettingsActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    SegmentedListItem(
        onClick = { open = true },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        supportingContent = { Text(stringResource(setting.summary)) },
    ) { Text(stringResource(setting.title)) }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(setting.title)) },
            text = { Text(stringResource(setting.summary)) },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    for ((path, default) in setting.defaults) {
                        // In a game, drop the override so the value follows the global settings again.
                        if (isGame) { if (path in state.overrides) actions.reset(path) } else actions.set(path, default)
                    }
                }) { Text(stringResource(R.string.settings_reset)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_key_cancel)) } },
        )
    }
}

/** Names of the game controllers connected now. */
private fun connectedControllerNames(): List<String> = InputDevice.getDeviceIds().toList().mapNotNull { id ->
    InputDevice.getDevice(id)?.takeIf {
        !it.isVirtual && ((it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (it.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
    }?.name
}

@Composable
private fun BiosManageRow(row: RowParams, actions: SettingsActions) {
    SegmentedListItem(
        onClick = actions::openBios,
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        supportingContent = { Supporting(BiosManageSetting) },
    ) { Text(stringResource(BiosManageSetting.title)) }
}

@Composable
private fun GpuDriverRow(row: RowParams, state: SettingsUiState, actions: SettingsActions) {
    SegmentedListItem(
        onClick = actions::openGpuDriver,
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        supportingContent = {
            Supporting(GpuDriverSetting, value = driverLabel(row.values.string(GpuDriverSetting.path!!), state.drivers))
        },
    ) { Text(stringResource(GpuDriverSetting.title)) }
}

/** The name of the driver [id] stored in the settings, as the boot will resolve it. */
@Composable
fun driverLabel(id: String, drivers: List<GpuDriver>): String = when {
    id.isEmpty() -> stringResource(R.string.driver_system)
    else -> drivers.firstOrNull { it.id == id }?.name ?: stringResource(R.string.driver_missing)
}

/** The text under a row's title: its current value, what it does, and when it takes effect. */
@Composable
private fun Supporting(setting: Setting, value: String? = null, showSummary: Boolean = true) {
    Column {
        if (value != null) Text(value, color = MaterialTheme.colorScheme.primary)
        if (showSummary && setting.summary != 0) Text(stringResource(setting.summary))
        if (setting.appliesOnNextBoot) {
            Text(
                stringResource(R.string.settings_applies_next_boot),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

private fun Setting.hasSupporting(value: Boolean = false) = value || summary != 0 || appliesOnNextBoot

@Composable
private fun ToggleRow(row: RowParams, setting: Toggle, actions: SettingsActions) {
    val path = setting.path!!
    val checked = row.values[path] == setting.on
    SegmentedListItem(
        checked = checked,
        onCheckedChange = { actions.set(path, if (it) setting.on else setting.off) },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = row.enabled) },
        overlineContent = row.overline,
        supportingContent = if (setting.hasSupporting()) ({ Supporting(setting) }) else null,
    ) { Text(stringResource(setting.title)) }
}

@Composable
private fun InlineChoiceRow(row: RowParams, setting: Choice, actions: SettingsActions) {
    val path = setting.path!!
    SegmentedListItem(
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        supportingContent = {
            Column {
                if (setting.hasSupporting()) Supporting(setting)
                Spacer(Modifier.height(12.dp))
                ConnectedChoice(setting.options, row.values[path], row.enabled) { actions.set(path, it) }
            }
        },
    ) { Text(stringResource(setting.title)) }
}

/** Options as a connected button group, the Expressive replacement for segmented buttons. */
@Composable
private fun ConnectedChoice(options: List<Option>, selected: Any?, enabled: Boolean, onSelect: (Any) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option.value == selected,
                onCheckedChange = { onSelect(option.value) },
                enabled = enabled,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(option.label.resolve(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DialogChoiceRow(row: RowParams, setting: Choice, actions: SettingsActions) {
    val path = setting.path!!
    val current = row.values[path]
    var open by rememberSaveable { mutableStateOf(false) }
    val label = setting.options.firstOrNull { it.value == current }?.label?.resolve() ?: current.toString()
    SegmentedListItem(
        onClick = { open = true },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        supportingContent = { Supporting(setting, value = label, showSummary = false) },
    ) { Text(stringResource(setting.title)) }

    if (open) {
        ChoiceDialog(
            setting = setting,
            selected = current,
            onSelect = {
                open = false
                if (it != current) actions.set(path, it)
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun ChoiceDialog(setting: Choice, selected: Any, onSelect: (Any) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.title)) },
        text = {
            Column {
                if (setting.summary != 0) {
                    Text(stringResource(setting.summary), modifier = Modifier.padding(bottom = 12.dp))
                }
                LazyColumn {
                    items(setting.options) { option ->
                        val isSelected = option.value == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option.value) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Text(option.label.resolve(), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun SliderRow(row: RowParams, setting: Slider, actions: SettingsActions) {
    val path = setting.path!!
    val range = setting.range
    val current = row.values.int(path)
    val stops = (range.last - range.first) / setting.step
    // Follows the finger while dragging; saved once the drag ends.
    val slider = remember(current) {
        SliderState(
            value = current.coerceIn(range).toFloat(),
            // Tick marks only while they stay far enough apart to read.
            steps = if (stops <= 20) stops - 1 else 0,
            trackRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
    fun snapped() = (((slider.value - range.first) / setting.step).roundToInt() * setting.step + range.first).coerceIn(range)
    SegmentedListItem(
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        trailingContent = { Text(sliderLabel(setting, snapped()), style = MaterialTheme.typography.labelLarge) },
        supportingContent = {
            Column {
                if (setting.hasSupporting()) Supporting(setting)
                Slider(
                    state = slider,
                    onValueChange = { slider.value = it },
                    onValueChangeFinished = {
                        val value = snapped()
                        slider.value = value.toFloat()
                        if (value != current) actions.set(path, value)
                    },
                    enabled = row.enabled,
                )
            }
        },
    ) { Text(stringResource(setting.title)) }
}

@Composable
private fun sliderLabel(setting: Slider, value: Int): String =
    setting.labels[value]?.resolve() ?: if (setting.unit != 0) stringResource(setting.unit, value) else value.toString()

@Composable
private fun NumberRow(row: RowParams, setting: NumberInput, actions: SettingsActions) {
    val path = setting.path!!
    val current = row.values[path] as Number
    var open by rememberSaveable { mutableStateOf(false) }
    SegmentedListItem(
        onClick = { open = true },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        supportingContent = { Supporting(setting, value = formatNumber(current), showSummary = false) },
    ) { Text(stringResource(setting.title)) }

    if (open) {
        NumberDialog(
            setting = setting,
            initial = formatNumber(current),
            onConfirm = {
                open = false
                actions.set(path, it)
            },
            onDismiss = { open = false },
        )
    }
}

private fun formatNumber(value: Number): String =
    if (value is Float || value is Double) {
        String.format(Locale.ROOT, "%.3f", value.toDouble()).trimEnd('0').trimEnd('.')
    } else {
        value.toString()
    }

@Composable
private fun NumberDialog(setting: NumberInput, initial: String, onConfirm: (Number) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val parsed = text.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it in setting.min..setting.max && (setting.decimal || it % 1.0 == 0.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.title)) },
        text = {
            Column {
                if (setting.summary != 0) {
                    Text(stringResource(setting.summary), modifier = Modifier.padding(bottom = 12.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = parsed == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (setting.decimal) KeyboardType.Decimal else KeyboardType.Number,
                    ),
                    supportingText = {
                        Text(stringResource(R.string.settings_number_range, formatNumber(setting.min), formatNumber(setting.max)))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onConfirm(if (setting.decimal) it.toFloat() else it.toInt()) } },
                enabled = parsed != null,
            ) { Text(stringResource(R.string.settings_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun TextRow(row: RowParams, setting: TextInput, actions: SettingsActions) {
    val path = setting.path!!
    val current = row.values.string(path)
    var open by rememberSaveable { mutableStateOf(false) }
    SegmentedListItem(
        onClick = { open = true },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        supportingContent = {
            Supporting(setting, value = current.ifEmpty { stringResource(R.string.settings_text_empty) }, showSummary = false)
        },
    ) { Text(stringResource(setting.title)) }

    if (open) {
        TextDialog(
            title = setting.title,
            summary = setting.summary,
            initial = current,
            multiline = setting.multiline,
            allowEmpty = setting.allowEmpty,
            hidden = false,
            onConfirm = {
                open = false
                actions.set(path, it)
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun GeminiKeyRow(row: RowParams) {
    val store = (LocalContext.current.applicationContext as AmetrineApplication).container.geminiKeyStore
    var isSet by remember { mutableStateOf(store.isSet()) }
    var open by rememberSaveable { mutableStateOf(false) }
    SegmentedListItem(
        onClick = { open = true },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        supportingContent = {
            Supporting(
                GeminiApiKeySetting,
                value = stringResource(if (isSet) R.string.settings_gemini_key_set else R.string.settings_gemini_key_unset),
                showSummary = false,
            )
        },
    ) { Text(stringResource(GeminiApiKeySetting.title)) }

    if (open) {
        TextDialog(
            title = GeminiApiKeySetting.title,
            summary = GeminiApiKeySetting.summary,
            initial = "",
            multiline = false,
            allowEmpty = true,
            hidden = true,
            onConfirm = {
                open = false
                store.set(it)
                isSet = store.isSet()
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun GeminiModelRow(row: RowParams, actions: SettingsActions) {
    val path = GeminiModelSetting.path!!
    val current = row.values.string(path)
    var open by rememberSaveable { mutableStateOf(false) }
    SegmentedListItem(
        onClick = { open = true },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        supportingContent = { Supporting(GeminiModelSetting, value = current, showSummary = false) },
    ) { Text(stringResource(GeminiModelSetting.title)) }

    if (open) {
        GeminiModelDialog(
            current = current,
            onSelect = {
                open = false
                actions.set(path, it)
            },
            onDismiss = { open = false },
        )
    }
}

/** Fetches the models the stored key can use each time it opens, so the list is never out of date. */
@Composable
private fun GeminiModelDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val store = (LocalContext.current.applicationContext as AmetrineApplication).container.geminiKeyStore
    var attempt by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<Result<List<GeminiModel>>?>(null) }
    LaunchedEffect(attempt) {
        result = null
        result = GeminiApi.listModels(store.get())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(GeminiModelSetting.title)) },
        text = {
            val models = result?.getOrNull()
            val error = result?.exceptionOrNull()
            when {
                result == null -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Text(
                    when (error) {
                        is GeminiError.NoKey -> stringResource(R.string.settings_gemini_models_no_key)
                        is GeminiError.Rejected -> stringResource(R.string.settings_gemini_models_rejected, error.message.orEmpty())
                        else -> stringResource(R.string.settings_gemini_models_network)
                    },
                )
                models != null -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    // The stored model stays selectable even if the list does not include it.
                    val all = if (models.any { it.id == current }) models else listOf(GeminiModel(current, current)) + models
                    items(all, key = { it.id }) { model ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = model.id == current, role = Role.RadioButton) { onSelect(model.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = model.id == current, onClick = null)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(model.displayName)
                                if (model.displayName != model.id) {
                                    Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (result?.isFailure == true && result?.exceptionOrNull() !is GeminiError.NoKey) {
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.settings_retry)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun TextDialog(
    @StringRes title: Int,
    @StringRes summary: Int,
    initial: String,
    multiline: Boolean,
    allowEmpty: Boolean,
    hidden: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val valid = allowEmpty || text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                if (summary != 0) {
                    Text(stringResource(summary), modifier = Modifier.padding(bottom = 12.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !multiline,
                    minLines = if (multiline) 3 else 1,
                    maxLines = if (multiline) 8 else 1,
                    isError = !valid,
                    visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = if (hidden) {
                        KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                    } else {
                        KeyboardOptions.Default
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = valid) {
                Text(stringResource(R.string.settings_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/** Marks a value the game sets for itself. Tapping it goes back to the global value. */
@Composable
private fun OverrideBadge(onReset: () -> Unit) {
    val description = stringResource(R.string.settings_override_badge_description)
    Surface(
        onClick = onReset,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.padding(bottom = 4.dp).semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.settings_this_game), style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

/** Shapes for item [index] of [count] in a section's group. */
@Composable
fun segmentedShapes(index: Int, count: Int): ListItemShapes = ListItemDefaults.segmentedShapes(index, count)

/**
 * Items stand out from the page as in Android's own settings: bright items on a container
 * background. Only [highlightSelected] rows tint when selected; a switch that is on does not.
 */
@Composable
fun settingsListColors(highlightSelected: Boolean = false): ListItemColors = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceBright,
    selectedContainerColor = if (highlightSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceBright,
    selectedContentColor = if (highlightSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
)
