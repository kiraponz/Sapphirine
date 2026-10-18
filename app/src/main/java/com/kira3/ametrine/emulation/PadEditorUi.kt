package com.kira3.ametrine.emulation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kira3.ametrine.R
import com.kira3.ametrine.emulation.input.PadEditor

/** Where the edited layout is stored. */
enum class PadLayoutScope { Game, All }

/**
 * The controls of the pad layout editor, over the pad. [hasGame] offers saving for just this game;
 * [onSave] is told which layout to store.
 */
@Composable
fun PadEditorBar(
    editor: PadEditor,
    hasGame: Boolean,
    onSave: (PadLayoutScope) -> Unit,
    onCancel: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        val landscape = maxWidth >= maxHeight
        val selected = editor.selected
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            // The tint has alpha, so the surface cannot work out its text color itself.
            contentColor = MaterialTheme.colorScheme.onSurface,
            // Upright, the pad and the function buttons of the controller layout are in the lower part
            // of the screen: stay over the picture.
            modifier = Modifier
                .align(if (landscape) Alignment.Center else Alignment.TopCenter)
                .widthIn(max = 360.dp)
                .fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.pad_edit_title), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !editor.controllerMode,
                        onClick = { editor.editController(false) },
                        label = { Text(stringResource(R.string.pad_edit_mode_pad)) },
                    )
                    FilterChip(
                        selected = editor.controllerMode,
                        onClick = { editor.editController(true) },
                        label = { Text(stringResource(R.string.pad_edit_mode_controller)) },
                    )
                }
                if (selected == null) {
                    Text(
                        stringResource(R.string.pad_edit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(padControlLabel(selected), color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.pad_edit_size), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = editor.scaleOf(landscape, selected),
                            onValueChange = { editor.setScale(landscape, selected, it) },
                            valueRange = 0.5f..2f,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { editor.reset(landscape) }) { Text(stringResource(R.string.pad_edit_reset)) }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.menu_cancel)) }
                }
                if (hasGame) {
                    Text(
                        stringResource(R.string.pad_edit_save_for),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onSave(PadLayoutScope.All) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.pad_edit_save_all))
                        }
                        Button(onClick = { onSave(PadLayoutScope.Game) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.pad_edit_save_game))
                        }
                    }
                } else {
                    Button(onClick = { onSave(PadLayoutScope.All) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pad_edit_save))
                    }
                }
            }
        }
    }
}

/** The name of a pad control for the editor, from its id (see TouchPadLayout.kt). */
@Composable
private fun padControlLabel(id: String): String = stringResource(
    when (id) {
        "dpad" -> R.string.pad_edit_dpad
        "stick.Left" -> R.string.pad_edit_stick_left
        "stick.Right" -> R.string.pad_edit_stick_right
        "extra.fastForward" -> R.string.emulation_fast_forward
        "extra.rewind" -> R.string.rewind_title
        "extra.screenshot" -> R.string.menu_screenshot
        "extra.macro" -> R.string.macro_title
        "extra.ai" -> R.string.ai_button
        "btn.A" -> R.string.pad_a
        "btn.B" -> R.string.pad_b
        "btn.C" -> R.string.pad_c
        "btn.X" -> R.string.pad_x
        "btn.Y" -> R.string.pad_y
        "btn.Z" -> R.string.pad_z
        "btn.L" -> R.string.pad_l
        "btn.R" -> R.string.pad_r
        "btn.Start" -> R.string.pad_start
        else -> R.string.pad_edit_title
    },
)
