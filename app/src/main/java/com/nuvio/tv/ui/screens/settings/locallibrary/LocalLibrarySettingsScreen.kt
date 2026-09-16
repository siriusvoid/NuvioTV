@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.data.locallibrary.LocalLibraryManager
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.quantityStringResource

@Composable
fun LocalLibrarySettingsScreen(
    onBackPress: () -> Unit,
    onNavigateToAddSource: () -> Unit,
    onNavigateToSourceDetail: (sourceId: String) -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsStandaloneScaffold(
        title = stringResource(R.string.settings_local_library_title),
        subtitle = stringResource(R.string.local_library_screen_subtitle)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.library_sources),
            subtitle = stringResource(R.string.local_library_sources_desc)
        )

        val anyScanning = state.progress.values.any {
            it is LocalLibraryManager.ScanProgress.Scanning ||
                it is LocalLibraryManager.ScanProgress.Matching
        }

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.library_source_add),
                subtitle = stringResource(R.string.local_library_add_source_sub),
                leadingIcon = Icons.Default.CreateNewFolder,
                onClick = onNavigateToAddSource
            )
            SettingsActionRow(
                title = stringResource(R.string.local_library_rescan_all),
                subtitle = stringResource(
                    if (anyScanning) R.string.library_scanning_in_progress
                    else R.string.local_library_rescan_all_sub
                ),
                leadingIcon = Icons.Default.Refresh,
                enabled = state.sources.isNotEmpty() && !anyScanning,
                onClick = { viewModel.rescanAllSources() }
            )
        }

        // Hidden until there are sources; an empty card says less than the Add source row.
        if (state.sources.isNotEmpty()) {
            SettingsGroupCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                title = stringResource(R.string.library_configured_sources)
            ) {
                val sourceListState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = sourceListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.sources, key = { it.id }) { source ->
                            SourceRow(
                                config = source,
                                progress = state.progress[source.id],
                                kindLabel = stringResource(viewModel.kindLabel(source.kind)),
                                onClick = { onNavigateToSourceDetail(source.id) }
                            )
                        }
                    }
                    SettingsVerticalScrollIndicators(state = sourceListState)
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    config: LocalLibrarySourceConfig,
    progress: LocalLibraryManager.ScanProgress?,
    kindLabel: String,
    onClick: () -> Unit
) {
    val statusText = when (progress) {
        is LocalLibraryManager.ScanProgress.Scanning ->
            stringResource(R.string.local_library_scanning_found, progress.itemsFound)
        is LocalLibraryManager.ScanProgress.Matching ->
            stringResource(R.string.library_scan_matching, progress.matched, progress.total)
        is LocalLibraryManager.ScanProgress.Failed -> stringResource(R.string.library_scan_failed, progress.reason)
        is LocalLibraryManager.ScanProgress.Idle ->
            quantityStringResource(R.plurals.local_library_items, progress.itemCount)
        null -> quantityStringResource(R.plurals.local_library_items, config.itemCount)
    }

    SettingsActionRow(
        title = config.displayName,
        subtitle = "$kindLabel · $statusText",
        value = if (config.enabled) null else stringResource(R.string.library_disabled),
        valueColor = NuvioTheme.colors.TextTertiary,
        leadingIcon = Icons.Default.Folder,
        titleTrailingIcon = if (progress is LocalLibraryManager.ScanProgress.Failed) {
            Icons.Default.ErrorOutline
        } else {
            null
        },
        titleTrailingIconTint = NuvioTheme.colors.Error,
        onClick = onClick
    )
}
