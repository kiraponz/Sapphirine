package com.kira3.ametrine.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.ametrine.R
import com.kira3.ametrine.emucore.Cheat
import com.kira3.ametrine.emucore.ActionReplay

/** A game's cheats: switch them on and off, add and edit codes, import a cheat file or the cheat database's codes. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CheatsScreen(
    serial: String,
    gameTitle: String?,
    onBack: () -> Unit,
    viewModel: CheatsViewModel = viewModel(key = "cheats:$serial", factory = CheatsViewModel.factory(serial)),
) {
    val cheats by viewModel.cheats.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    // The cheat being edited, or [NEW] for a new one.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var importMenu by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }

    val importedText = imported?.let {
        if (it < 0) stringResource(R.string.cheats_import_none) else pluralStringResource(R.plurals.cheats_imported, it, it)
    }
    LaunchedEffect(imported) {
        if (importedText != null) {
            snackbarHostState.showSnackbar(importedText)
            viewModel.onImportShown()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_cheats)) },
                subtitle = gameTitle?.let { { Text(it, maxLines = 1) } },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    Box {
                        TextButton(onClick = { importMenu = true }) {
                            Text(stringResource(R.string.cheats_import))
                        }
                        DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cheats_import_database)) },
                                onClick = {
                                    importMenu = false
                                    viewModel.importFromDatabase()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cheats_import_file)) },
                                onClick = {
                                    importMenu = false
                                    pickFile.launch(arrayOf("*/*"))
                                },
                            )
                        }
                    }
                },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editingId = NEW },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.cheats_add)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val list = cheats ?: return@Scaffold
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(list, key = { _, cheat -> cheat.id }) { index, cheat ->
                SegmentedListItem(
                    onClick = { editingId = cheat.id },
                    shapes = segmentedShapes(index, list.size),
                    colors = settingsListColors(),
                    modifier = Modifier.animateItem(),
                    trailingContent = {
                        Switch(checked = cheat.enabled, onCheckedChange = { viewModel.setEnabled(cheat.id, it) })
                    },
                    supportingContent = {
                        val lines = ActionReplay.check(cheat.code).lines.size
                        Text(pluralStringResource(R.plurals.cheats_code_lines, lines, lines))
                    },
                ) { Text(cheat.name, maxLines = 1) }
            }
            item(key = "about") {
                Text(
                    stringResource(if (list.isEmpty()) R.string.cheats_empty else R.string.cheats_about),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Room for the floating button over the last line.
                    modifier = Modifier.animateItem().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 72.dp),
                )
            }
        }

        editingId?.let { id ->
            val existing = list.firstOrNull { it.id == id }
            if (id == NEW || existing != null) {
                CheatDialog(
                    cheat = existing,
                    onSave = { name, code ->
                        viewModel.save(existing?.id, name, code)
                        editingId = null
                    },
                    onDelete = existing?.let { { viewModel.delete(it.id); editingId = null } },
                    onDismiss = { editingId = null },
                )
            } else {
                editingId = null
            }
        }
    }
}

private const val NEW = "new"

@Composable
private fun CheatDialog(cheat: Cheat?, onSave: (name: String, code: String) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(cheat?.name.orEmpty()) }
    var code by rememberSaveable { mutableStateOf(cheat?.code.orEmpty()) }
    val check = ActionReplay.check(code)
    val canSave = name.isNotBlank() && code.isNotBlank() && check.badLine == null && check.lines.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (cheat == null) R.string.cheats_add else R.string.cheats_edit)) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.cheats_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.cheats_code)) },
                    supportingText = {
                        Text(
                            check.badLine?.let { stringResource(R.string.cheats_bad_line, it) }
                                ?: stringResource(R.string.cheats_code_hint),
                        )
                    },
                    isError = check.badLine != null,
                    minLines = 4,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, code) }, enabled = canSave) { Text(stringResource(R.string.cheats_save)) }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.cheats_delete)) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            }
        },
    )
}
