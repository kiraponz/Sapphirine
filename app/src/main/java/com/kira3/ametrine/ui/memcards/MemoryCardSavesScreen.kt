package com.kira3.ametrine.ui.memcards

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.ametrine.R
import com.kira3.ametrine.data.MemoryCard
import com.kira3.ametrine.data.memcard.CardState
import com.kira3.ametrine.data.memcard.SaveInfo
import com.kira3.ametrine.ui.settings.BackButton
import com.kira3.ametrine.ui.settings.listPadding
import com.kira3.ametrine.ui.settings.segmentedShapes
import com.kira3.ametrine.ui.settings.settingsListColors
import com.kira3.ametrine.ui.settings.settingsTopBarColors
import java.util.Date

/**
 * The saves on one memory card. Tick some to copy or move them to another card, or delete them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MemoryCardSavesScreen(
    cardName: String,
    onBack: () -> Unit,
    viewModel: MemoryCardSavesViewModel = viewModel(key = cardName, factory = MemoryCardSavesViewModel.factory(cardName)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Beside the settings list there is nothing to go back to.
    val twoPane = LocalListDetailSceneScope.current != null
    val snackbarHostState = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<Boolean?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val saves = state.listing?.saves.orEmpty()

    val messages = mapOf(
        MemoryCardMessage.SavesCopied to stringResource(R.string.memcards_saves_copied),
        MemoryCardMessage.SavesMoved to stringResource(R.string.memcards_saves_moved),
        MemoryCardMessage.SavesDeleted to stringResource(R.string.memcards_saves_deleted),
        MemoryCardMessage.Full to stringResource(R.string.memcards_full),
        MemoryCardMessage.Busy to stringResource(R.string.memcards_busy),
        MemoryCardMessage.Exists to stringResource(R.string.memcards_exists),
        MemoryCardMessage.Invalid to stringResource(R.string.memcards_invalid),
        MemoryCardMessage.Failed to stringResource(R.string.memcards_failed),
    )
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        messages[message]?.let { snackbarHostState.showSnackbar(it) }
        viewModel.onMessageShown()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(state.card?.label ?: cardName, maxLines = 1) },
                navigationIcon = { if (!twoPane) BackButton(onBack) },
                actions = {
                    if (saves.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                if (state.selected.size == saves.size) viewModel.clearSelection()
                                else viewModel.selectAll(saves.map { it.name })
                            },
                            enabled = !state.busy,
                        ) {
                            Text(
                                stringResource(
                                    if (state.selected.size == saves.size) R.string.memcards_select_none else R.string.memcards_select_all,
                                ),
                            )
                        }
                    }
                },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (state.selected.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pluralStringResource(R.plurals.memcards_selected, state.selected.size, state.selected.size),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        val canTransfer = !state.busy && state.others.isNotEmpty()
                        TextButton(onClick = { picking = false }, enabled = canTransfer) {
                            Text(stringResource(R.string.memcards_copy_to))
                        }
                        TextButton(onClick = { picking = true }, enabled = canTransfer) {
                            Text(stringResource(R.string.memcards_move_to))
                        }
                        TextButton(onClick = { deleting = true }, enabled = !state.busy) {
                            Text(stringResource(R.string.memcards_delete))
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (!state.loaded || state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(16.dp))
            if (!state.loaded) return@Scaffold
        }
        val listing = state.listing
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item(key = "summary") {
                Text(
                    summary(state.card, listing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (saves.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (listing == null || listing.state == CardState.Unsupported) R.string.memcards_unreadable else R.string.memcards_no_saves),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(saves, key = { _, save -> save.name }) { index, save ->
                val checked = save.name in state.selected
                SegmentedListItem(
                    checked = checked,
                    onCheckedChange = { viewModel.toggle(save.name) },
                    shapes = segmentedShapes(index, saves.size),
                    colors = settingsListColors(),
                    modifier = Modifier.animateItem(),
                    enabled = !state.busy,
                    leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    supportingContent = { Text(save.details()) },
                ) {
                    Text(save.title ?: save.name, maxLines = 2)
                }
            }
        }
    }

    picking?.let { move ->
        TargetDialog(
            move = move,
            others = state.others,
            onPick = {
                picking = null
                viewModel.transfer(it, move)
            },
            onDismiss = { picking = null },
        )
    }
    state.conflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = { Text(stringResource(R.string.memcards_replace_title, conflict.target.label)) },
            text = {
                Text(pluralStringResource(R.plurals.memcards_replace_text, conflict.existing.size, conflict.existing.size))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveConflict(replace = true) }) {
                    Text(stringResource(R.string.memcards_replace))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.resolveConflict(replace = false) }) {
                        Text(stringResource(R.string.memcards_skip))
                    }
                    TextButton(onClick = viewModel::dismissConflict) { Text(stringResource(R.string.settings_cancel)) }
                }
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = {
                Text(pluralStringResource(R.plurals.memcards_delete_saves_title, state.selected.size, state.selected.size))
            },
            text = { Text(stringResource(R.string.memcards_delete_saves_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    viewModel.deleteSelected()
                }) { Text(stringResource(R.string.memcards_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
private fun summary(card: MemoryCard?, listing: com.kira3.ametrine.data.SaveListing?): String {
    if (card == null) return stringResource(R.string.memcards_gone)
    return when (listing?.state) {
        CardState.Formatted -> stringResource(R.string.memcards_free_of, listing.freeBlocks, listing.capacityBlocks)
        CardState.Unsupported -> stringResource(R.string.memcards_not_formatted)
        null -> ""
    }
}

@Composable
private fun SaveInfo.details(): String {
    val parts = listOfNotNull(
        if (title != null) name else null,
        pluralStringResource(R.plurals.memcards_save_blocks, blocks, blocks),
        date?.let { DateUtils.formatDateTime(LocalContext.current, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) },
    )
    return parts.joinToString(" · ")
}

@Composable
private fun TargetDialog(move: Boolean, others: List<MemoryCard>, onPick: (MemoryCard) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<MemoryCard?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (move) R.string.memcards_move_to else R.string.memcards_copy_to)) },
        text = {
            Column {
                for (card in others) {
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = card },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected?.name == card.name, onClick = { selected = card })
                        Text(card.label, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let(onPick) }) {
                Text(stringResource(if (move) R.string.memcards_move else R.string.memcards_copy))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
