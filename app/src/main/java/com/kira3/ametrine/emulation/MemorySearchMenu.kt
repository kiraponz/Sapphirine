package com.kira3.ametrine.emulation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kira3.ametrine.R
import com.kira3.ametrine.emucore.EmulatorCore
import com.kira3.ametrine.emulation.search.MemorySearch
import com.kira3.ametrine.emulation.search.MemorySearch.Mode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds the address of a number in the game's memory (a character's HP, say): scan for the value,
 * play until it changes, scan again with the new value. The state outlives the panel, so it can be
 * closed to let the game run.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MemorySearchMenu(search: MemorySearch, editable: Boolean, onClose: () -> Unit) {
    val state = search.state
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val text = search.valueText
    val mode = search.mode
    val value = text.trim().toLongOrNull()?.takeIf { it >= 0 }
    var editing by remember { mutableStateOf<List<MemorySearch.Hit>?>(null) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    val chosen = state.hits.filter { it.address in selected }

    // Live values of the listed addresses, while the panel is up (they move once the game runs).
    LaunchedEffect(state.hits.size) {
        while (state.hits.isNotEmpty()) {
            delay(500)
            search.refresh()
        }
    }

    editing?.let { EditValueDialog(search, it) { editing = null } }

    // Holding the title makes the panel see-through, to look at the game behind it.
    var peeking by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(if (peeking) 0.12f else 1f, label = "peek")

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f * fade))
            .clickable(interactionSource = null, indication = null, onClick = onClose)
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .graphicsLayer { alpha = fade }
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp, horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.search_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp).holdToPeek { peeking = it },
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.menu_resume)) }
                }
                Text(
                    stringResource(if (state.started) R.string.search_hint_next else R.string.search_hint_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.search_peek_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (size in listOf(1, 2, 4)) {
                        FilterChip(
                            selected = state.size == size,
                            onClick = { search.setSize(size) },
                            enabled = !state.started && !state.busy,
                            label = { Text(stringResource(R.string.search_size, size)) },
                        )
                    }
                }
                if (state.started) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in Mode.entries) {
                            FilterChip(
                                selected = mode == m,
                                onClick = { search.mode = m },
                                label = { Text(stringResource(modeLabel(m))) },
                            )
                        }
                    }
                }
                if (!state.started || mode == Mode.Exact) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { search.valueText = it.filter(Char::isDigit).take(10) },
                        label = { Text(stringResource(R.string.search_value)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!state.started) {
                        Button(
                            onClick = { scope.launch { search.first(value) } },
                            enabled = value != null && !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.search_first)) }
                        OutlinedButton(
                            onClick = { scope.launch { search.first(null) } },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.search_unknown)) }
                    } else {
                        Button(
                            onClick = { scope.launch { search.next(mode, value) } },
                            enabled = (mode != Mode.Exact || value != null) && !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.search_next)) }
                        OutlinedButton(
                            onClick = search::reset,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.search_reset)) }
                    }
                }
                when {
                    state.busy -> Text(stringResource(R.string.search_busy), style = MaterialTheme.typography.bodyMedium)
                    state.problem == MemorySearch.Problem.NoGame ->
                        Text(stringResource(R.string.search_no_game), color = MaterialTheme.colorScheme.error)
                    state.problem == MemorySearch.Problem.TooMany ->
                        Text(stringResource(R.string.search_too_many), color = MaterialTheme.colorScheme.error)
                    state.started -> Text(
                        stringResource(R.string.search_count, state.count),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (editable && state.hits.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                selected = if (chosen.size == state.hits.size) emptySet() else state.hits.map { it.address }.toSet()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(if (chosen.size == state.hits.size) R.string.search_select_none else R.string.search_select_all))
                        }
                        Button(
                            onClick = { editing = chosen },
                            enabled = chosen.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.search_change_selected, chosen.size)) }
                    }
                }
                for (hit in state.hits) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (editable) {
                                        selected = if (hit.address in selected) selected - hit.address else selected + hit.address
                                    } else {
                                        copyAddress(context, hit.address)
                                    }
                                },
                                onLongClick = { copyAddress(context, hit.address) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (editable) {
                            Checkbox(checked = hit.address in selected, onCheckedChange = null)
                            Spacer(Modifier.width(16.dp))
                        }
                        Text(
                            saturnAddress(hit.address),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(hit.value.toString(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (state.hits.isNotEmpty()) {
                    Text(
                        stringResource(if (editable) R.string.search_tap_to_edit else R.string.search_tap_to_copy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Asks for a new value for [hit] and writes it. */
@Composable
private fun EditValueDialog(search: MemorySearch, hits: List<MemorySearch.Hit>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val max = (1L shl (8 * search.state.size)) - 1
    // Start from the value they share, if they do.
    var text by remember { mutableStateOf(hits.map { it.value }.distinct().singleOrNull()?.toString().orEmpty()) }
    val value = text.toLongOrNull()?.takeIf { it in 0..max }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (hits.size == 1) saturnAddress(hits[0].address)
                    else stringResource(R.string.search_edit_many, hits.size),
                    fontFamily = FontFamily.Monospace,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(10) },
                    label = { Text(stringResource(R.string.search_edit_value, max.toString())) },
                    singleLine = true,
                    isError = value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null,
                onClick = {
                    scope.launch {
                        if (!search.write(hits.map { it.address }, value!!)) {
                            Toast.makeText(context, R.string.search_edit_failed, Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.search_edit_write)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.menu_cancel)) } },
    )
}

/** Tells [onHold] true once the pointer has stayed down for the long-press time, and false when it lifts. */
private fun Modifier.holdToPeek(onHold: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
        if (lifted == null) {
            onHold(true)
            try {
                waitForUpOrCancellation()
            } finally {
                onHold(false)
            }
        }
    }
}

private fun modeLabel(mode: Mode) = when (mode) {
    Mode.Exact -> R.string.search_mode_exact
    Mode.Changed -> R.string.search_mode_changed
    Mode.Unchanged -> R.string.search_mode_unchanged
    Mode.Increased -> R.string.search_mode_increased
    Mode.Decreased -> R.string.search_mode_decreased
}

/** The hit's offset and its real Saturn address (the memory search keeps offsets inside), as hex. */
private fun saturnAddress(offset: Int) = "%08X".format(EmulatorCore.offsetToSaturnAddress(offset))

private fun copyAddress(context: Context, address: Int) {
    val text = saturnAddress(address)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("address", text))
    Toast.makeText(context, context.getString(R.string.search_copied, text), Toast.LENGTH_SHORT).show()
}
