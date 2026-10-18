package com.kira3.ametrine.ui.bios

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.ametrine.R
import com.kira3.ametrine.data.Bios
import com.kira3.ametrine.emucore.BiosRegion
import com.kira3.ametrine.ui.settings.BackButton
import com.kira3.ametrine.ui.settings.listPadding
import com.kira3.ametrine.ui.settings.segmentedShapes
import com.kira3.ametrine.ui.settings.settingsListColors
import com.kira3.ametrine.ui.settings.settingsTopBarColors

/**
 * The BIOS files the app holds: pick the one games boot with, add files, delete them.
 * (A game can pick its own BIOS in its settings.)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun BiosScreen(
    onBack: () -> Unit,
    viewModel: BiosViewModel = viewModel(factory = BiosViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Bios?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Beside the settings list there is nothing to go back to.
    val twoPane = LocalListDetailSceneScope.current != null
    val snackbarHostState = remember { SnackbarHostState() }

    val pickBios = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onBiosPicked)
    }

    val invalidText = stringResource(R.string.bios_invalid)
    LaunchedEffect(state.invalidFile) {
        if (state.invalidFile) {
            snackbarHostState.showSnackbar(invalidText)
            viewModel.onInvalidFileShown()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.bios_title)) },
                navigationIcon = { if (!twoPane) BackButton(onBack) },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!state.importing) pickBios.launch(arrayOf("*/*")) },
                icon = {
                    if (state.importing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    else Icon(Icons.Filled.Add, contentDescription = null)
                },
                text = { Text(stringResource(if (state.importing) R.string.bios_adding else R.string.bios_add)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val installed = state.installed
        if (installed == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            if (installed.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.bios_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(installed, key = { _, bios -> bios.fileName }) { index, bios ->
                SegmentedListItem(
                    shapes = segmentedShapes(index, installed.size),
                    colors = settingsListColors(),
                    modifier = Modifier.animateItem(),
                    trailingContent = {
                        IconButton(onClick = { deleting = bios }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.bios_delete))
                        }
                    },
                    supportingContent = { Text(bios.details()) },
                ) {
                    Text(bios.fileName, maxLines = 1)
                }
            }
            item(key = "about") {
                Text(
                    stringResource(R.string.bios_about),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Room for the floating button over the last line.
                    modifier = Modifier.animateItem().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 72.dp),
                )
            }
        }
    }

    deleting?.let { bios ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.bios_delete_title, bios.fileName)) },
            text = { Text(stringResource(R.string.bios_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(bios)
                }) { Text(stringResource(R.string.bios_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

/** E.g. "Japan · sega_101.bin", or just "Unknown BIOS" for an image the core does not know. */
@Composable
private fun Bios.details(): String {
    val region = stringResource(metadata.region.labelRes)
    return if (metadata.region == BiosRegion.Unknown) region else "$region · ${metadata.description}"
}

private val BiosRegion.labelRes: Int
    get() = when (this) {
        BiosRegion.Japan -> R.string.bios_region_japan
        BiosRegion.NorthAmericaEurope -> R.string.bios_region_na_eu
        BiosRegion.Unknown -> R.string.bios_region_unknown
    }
