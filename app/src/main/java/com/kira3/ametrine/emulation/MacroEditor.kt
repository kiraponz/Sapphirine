package com.kira3.ametrine.emulation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kira3.ametrine.R
import com.kira3.ametrine.data.macro.Macro
import com.kira3.ametrine.data.macro.MacroEvent
import com.kira3.ametrine.data.macro.MacroWait
import com.kira3.ametrine.data.macro.normalized
import com.kira3.ametrine.emucore.PadButton
import com.kira3.ametrine.emucore.PadStick

/**
 * Edits a macro's inputs, wait points and length. Nothing is kept until Save. [suggestions] are
 * the addresses (and sizes) the memory search has narrowed down, to pick from for a wait point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditorScreen(
    macro: Macro,
    suggestions: List<Pair<Int, Int>>,
    onSave: (Macro) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember(macro.id) { mutableStateOf(macro) }
    // The item being edited: its index, or [NEW] for one to add.
    var waitAt by remember { mutableStateOf<Int?>(null) }
    var eventAt by remember { mutableStateOf<Int?>(null) }
    var lengthOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(macro.name, maxLines = 1) },
                actions = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.menu_cancel)) }
                    TextButton(onClick = { onSave(draft.normalized()) }) { Text(stringResource(R.string.macro_editor_save)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item(key = "length") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.macro_editor_length)) },
                    supportingContent = { Text(frameLabel(draft.length)) },
                    modifier = Modifier.clickable { lengthOpen = true },
                )
            }
            item(key = "waits-title") { SectionTitle(R.string.macro_editor_waits) }
            itemsIndexed(draft.waits, key = { i, _ -> "w$i" }) { index, wait ->
                ListItem(
                    overlineContent = { Text(frameLabel(wait.t)) },
                    headlineContent = { Text(wait.condition.describe(), fontFamily = FontFamily.Monospace) },
                    supportingContent = {
                        Text(
                            wait.timeout?.let { stringResource(R.string.macro_wait_gives_up, (it + 59) / 60) }
                                ?: stringResource(R.string.macro_wait_forever),
                        )
                    },
                    trailingContent = {
                        DeleteButton { draft = draft.copy(waits = draft.waits.filterIndexed { i, _ -> i != index }) }
                    },
                    modifier = Modifier.clickable { waitAt = index },
                )
            }
            item(key = "waits-add") {
                FilledTonalButton(
                    onClick = { waitAt = NEW },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text(stringResource(R.string.macro_editor_add_wait)) }
            }
            item(key = "inputs-title") { SectionTitle(R.string.macro_editor_inputs) }
            itemsIndexed(draft.events, key = { i, _ -> "e$i" }) { index, event ->
                ListItem(
                    overlineContent = { Text(frameLabel(event.t)) },
                    headlineContent = { Text(eventLabel(event)) },
                    trailingContent = {
                        DeleteButton { draft = draft.copy(events = draft.events.filterIndexed { i, _ -> i != index }) }
                    },
                    modifier = Modifier.clickable { eventAt = index },
                )
            }
            item(key = "inputs-add") {
                FilledTonalButton(
                    onClick = { eventAt = NEW },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text(stringResource(R.string.macro_editor_add_input)) }
            }
        }
    }

    waitAt?.let { index ->
        val existing = draft.waits.getOrNull(index)
        ConditionDialog(
            kind = ConditionKind.Wait,
            current = existing?.condition,
            suggestions = suggestions,
            onDismiss = { waitAt = null },
            showFrame = true,
            initialFrame = existing?.t ?: 0L,
            initialTimeoutSeconds = existing?.timeout?.let { (it + 59) / 60 },
            onSave = { condition, seconds, frame ->
                waitAt = null
                if (condition != null) {
                    val wait = MacroWait(frame, condition, seconds?.let(::secondsToFrames))
                    draft = draft.copy(
                        waits = if (existing == null) draft.waits + wait else draft.waits.mapIndexed { i, w -> if (i == index) wait else w },
                    ).normalized()
                }
            },
        )
    }
    eventAt?.let { index ->
        val existing = draft.events.getOrNull(index)
        EventDialog(
            initial = existing,
            onDismiss = { eventAt = null },
            onSave = { event ->
                eventAt = null
                draft = draft.copy(
                    events = if (existing == null) draft.events + event else draft.events.mapIndexed { i, e -> if (i == index) event else e },
                ).normalized()
            },
        )
    }
    if (lengthOpen) {
        val shortest = maxOf(draft.events.maxOfOrNull { it.t } ?: 0L, draft.waits.maxOfOrNull { it.t } ?: 0L)
        var text by remember { mutableStateOf(draft.length.toString()) }
        val frames = text.toLongOrNull()?.takeIf { it >= shortest }
        AlertDialog(
            onDismissRequest = { lengthOpen = false },
            title = { Text(stringResource(R.string.macro_editor_length)) },
            text = {
                OutlinedTextField(
                    text,
                    { text = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.macro_editor_frames)) },
                    supportingText = { Text(stringResource(R.string.macro_editor_length_hint, shortest)) },
                    isError = frames == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = frames != null, onClick = { draft = draft.copy(length = frames!!); lengthOpen = false }) {
                    Text(stringResource(R.string.macro_condition_save))
                }
            },
            dismissButton = { TextButton(onClick = { lengthOpen = false }) { Text(stringResource(R.string.menu_cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun DeleteButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.macro_delete))
    }
}

/** `120 (2.00 s)`: a frame count with the seconds it is about. */
private fun frameLabel(frames: Long): String = "%d (%.2f s)".format(frames, frames / FRAMES_PER_SECOND_F)

private const val FRAMES_PER_SECOND_F = 60.0

@Composable
private fun eventLabel(e: MacroEvent): String = when {
    e.button != null -> stringResource(
        if (e.pressed) R.string.macro_event_pressed_desc else R.string.macro_event_released_desc,
        e.button,
    )
    else -> stringResource(R.string.macro_event_stick_desc, e.stick.orEmpty(), "%.2f".format(e.x), "%.2f".format(e.y))
}

private const val NEW = -1

/** Adds or changes one recorded input: when, which button (pressed or let go) or stick (and where). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventDialog(initial: MacroEvent?, onDismiss: () -> Unit, onSave: (MacroEvent) -> Unit) {
    var frame by remember { mutableStateOf((initial?.t ?: 0L).toString()) }
    var stickMode by remember { mutableStateOf(initial?.stick != null) }
    var button by remember { mutableStateOf(PadButton.entries.firstOrNull { it.name == initial?.button } ?: PadButton.A) }
    var pressed by remember { mutableStateOf(initial?.let { it.button == null || it.pressed } ?: true) }
    var stick by remember { mutableStateOf(PadStick.entries.firstOrNull { it.name == initial?.stick } ?: PadStick.Left) }
    var x by remember { mutableStateOf((initial?.x ?: 0f).toString()) }
    var y by remember { mutableStateOf((initial?.y ?: 0f).toString()) }

    val t = frame.toLongOrNull()
    val xf = x.toFloatOrNull()?.takeIf { it in -1f..1f }
    val yf = y.toFloatOrNull()?.takeIf { it in -1f..1f }
    val result: MacroEvent? = when {
        t == null -> null
        stickMode -> if (xf != null && yf != null) MacroEvent(t, stick = stick.name, x = xf, y = yf) else null
        else -> MacroEvent(t, button = button.name, pressed = pressed, value = initial?.value ?: 1f)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.macro_editor_input_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    frame,
                    { frame = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.macro_editor_at_frame)) },
                    supportingText = { Text(stringResource(R.string.macro_editor_frame_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!stickMode, { stickMode = false }, label = { Text(stringResource(R.string.macro_event_button)) })
                    FilterChip(stickMode, { stickMode = true }, label = { Text(stringResource(R.string.macro_event_stick)) })
                }
                if (stickMode) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (s in PadStick.entries) FilterChip(stick == s, { stick = s }, label = { Text(s.name) })
                    }
                    OutlinedTextField(
                        x,
                        { x = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(6) },
                        label = { Text(stringResource(R.string.macro_event_x)) },
                        isError = xf == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        y,
                        { y = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(6) },
                        label = { Text(stringResource(R.string.macro_event_y)) },
                        supportingText = { Text(stringResource(R.string.macro_event_stick_hint)) },
                        isError = yf == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (b in PadButton.entries) FilterChip(button == b, { button = b }, label = { Text(b.name) })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(pressed, { pressed = true }, label = { Text(stringResource(R.string.macro_event_pressed)) })
                        FilterChip(!pressed, { pressed = false }, label = { Text(stringResource(R.string.macro_event_released)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = result != null, onClick = { onSave(result!!) }) {
                Text(stringResource(R.string.macro_condition_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.menu_cancel)) } },
    )
}
