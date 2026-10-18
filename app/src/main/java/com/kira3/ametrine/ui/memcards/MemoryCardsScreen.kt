package com.kira3.ametrine.ui.memcards

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.ametrine.R
import com.kira3.ametrine.data.MemoryCard
import com.kira3.ametrine.ui.settings.BackButton
import com.kira3.ametrine.ui.settings.listPadding
import com.kira3.ametrine.ui.settings.segmentedShapes
import com.kira3.ametrine.ui.settings.settingsListColors
import com.kira3.ametrine.ui.settings.settingsTopBarColors
import java.util.Date

/**
 * The memory cards the app holds: create one, copy one in from a file or out to a file,
 * rename and delete. (Which card a game uses is chosen in the settings.)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MemoryCardsScreen(
    onOpenCard: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: MemoryCardsViewModel = viewModel(factory = MemoryCardsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<MemoryCard?>(null) }
    var deleting by remember { mutableStateOf<MemoryCard?>(null) }
    var formatting by remember { mutableStateOf<MemoryCard?>(null) }
    var exporting by remember { mutableStateOf<MemoryCard?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Beside the settings list there is nothing to go back to.
    val twoPane = LocalListDetailSceneScope.current != null
    val snackbarHostState = remember { SnackbarHostState() }

    val pickCard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }
    val saveCard = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val card = exporting
        exporting = null
        if (uri != null && card != null) viewModel.export(card, uri)
    }

    val messages = mapOf(
        MemoryCardMessage.Created to stringResource(R.string.memcards_created),
        MemoryCardMessage.Imported to stringResource(R.string.memcards_imported),
        MemoryCardMessage.Exported to stringResource(R.string.memcards_exported),
        MemoryCardMessage.Renamed to stringResource(R.string.memcards_renamed),
        MemoryCardMessage.Deleted to stringResource(R.string.memcards_deleted),
        MemoryCardMessage.Formatted to stringResource(R.string.memcards_formatted),
        MemoryCardMessage.Exists to stringResource(R.string.memcards_exists),
        MemoryCardMessage.Invalid to stringResource(R.string.memcards_invalid),
        MemoryCardMessage.Failed to stringResource(R.string.memcards_failed),
        MemoryCardMessage.Busy to stringResource(R.string.memcards_busy),
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
                title = { Text(stringResource(R.string.memcards_title)) },
                navigationIcon = { if (!twoPane) BackButton(onBack) },
                actions = {
                    TextButton(onClick = { pickCard.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                        Text(stringResource(R.string.memcards_import))
                    }
                },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!state.busy) creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.memcards_create)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val cards = state.cards
        if (cards == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            if (cards.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.memcards_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(cards, key = { _, card -> card.name }) { index, card ->
                SegmentedListItem(
                    onClick = { onOpenCard(card.name) },
                    shapes = segmentedShapes(index, cards.size),
                    colors = settingsListColors(),
                    modifier = Modifier.animateItem(),
                    trailingContent = {
                        CardMenu(
                            enabled = !state.busy,
                            onExport = {
                                exporting = card
                                saveCard.launch(viewModel.exportName(card))
                            },
                            onRename = { renaming = card },
                            onFormat = { formatting = card },
                            onDelete = { deleting = card },
                        )
                    },
                    supportingContent = { Text(card.details()) },
                ) {
                    Text(card.label, maxLines = 1)
                }
            }
            item(key = "about") {
                Text(
                    stringResource(R.string.memcards_about),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Room for the floating button over the last line.
                    modifier = Modifier.animateItem().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 72.dp),
                )
            }
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.memcards_create),
            initial = "",
            confirm = stringResource(R.string.memcards_create),
            onConfirm = { name ->
                creating = false
                viewModel.create(name)
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { card ->
        NameDialog(
            title = stringResource(R.string.memcards_rename),
            initial = card.name,
            confirm = stringResource(R.string.memcards_rename),
            onConfirm = {
                renaming = null
                viewModel.rename(card, it)
            },
            onDismiss = { renaming = null },
        )
    }
    formatting?.let { card ->
        AlertDialog(
            onDismissRequest = { formatting = null },
            title = { Text(stringResource(R.string.memcards_format_title, card.label)) },
            text = { Text(stringResource(R.string.memcards_format_text)) },
            confirmButton = {
                TextButton(onClick = {
                    formatting = null
                    viewModel.format(card)
                }) { Text(stringResource(R.string.memcards_format)) }
            },
            dismissButton = {
                TextButton(onClick = { formatting = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.memcards_delete_title, card.label)) },
            text = { Text(stringResource(R.string.memcards_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(card)
                }) { Text(stringResource(R.string.memcards_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun CardMenu(enabled: Boolean, onExport: () -> Unit, onRename: () -> Unit, onFormat: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.memcards_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.memcards_export)) },
                onClick = { open = false; onExport() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.memcards_rename)) },
                onClick = { open = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.memcards_format)) },
                onClick = { open = false; onFormat() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.memcards_delete)) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/** When the card was last written. */
@Composable
private fun MemoryCard.details(): String = DateFormat.getMediumDateFormat(LocalContext.current).format(Date(modified))

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.memcards_name)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
