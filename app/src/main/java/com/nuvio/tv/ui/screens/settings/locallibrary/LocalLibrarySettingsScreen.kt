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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.data.locallibrary.LocalLibraryManager
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.theme.NuvioTheme

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
        title = "Local sources",
        subtitle = "Scan folders on this device for media."
    ) {
        SettingsDetailHeader(
            title = "Sources",
            subtitle = "Each source is scanned and matched to TMDB so it appears alongside addon content."
        )

        val anyScanning = state.progress.values.any {
            it is LocalLibraryManager.ScanProgress.Scanning ||
                it is LocalLibraryManager.ScanProgress.Matching
        }

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = "Add source…",
                subtitle = "On-device folder",
                leadingIcon = Icons.Default.CreateNewFolder,
                onClick = onNavigateToAddSource
            )
            SettingsActionRow(
                title = "Rescan all sources",
                subtitle = if (anyScanning) "Scanning in progress…"
                    else "Re-index every configured source",
                leadingIcon = Icons.Default.Refresh,
                enabled = state.sources.isNotEmpty() && !anyScanning,
                onClick = { viewModel.rescanAllSources() }
            )
        }

        // An empty card headed "Configured sources" says less than the Add source row
        // above it already does, so it only appears once there is something to list.
        if (state.sources.isNotEmpty()) {
            SettingsGroupCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                title = "Configured sources"
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
                                kindLabel = viewModel.kindLabel(source.kind),
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
        is LocalLibraryManager.ScanProgress.Scanning -> "Scanning… ${progress.itemsFound} found"
        is LocalLibraryManager.ScanProgress.Matching -> "Matching ${progress.matched}/${progress.total}"
        is LocalLibraryManager.ScanProgress.Failed -> "Failed: ${progress.reason}"
        is LocalLibraryManager.ScanProgress.Idle -> "${progress.itemCount} items"
        null -> "${config.itemCount} items"
    }

    SettingsActionRow(
        title = config.displayName,
        subtitle = "$kindLabel · $statusText",
        value = if (config.enabled) null else "Disabled",
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
