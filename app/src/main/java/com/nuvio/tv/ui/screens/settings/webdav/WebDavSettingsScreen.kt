@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.webdav

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
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.data.webdav.WebDavSourceCounts
import com.nuvio.tv.domain.model.webdav.ScanPhase
import com.nuvio.tv.domain.model.webdav.WebDavScanProgress
import com.nuvio.tv.domain.model.webdav.WebDavSource
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun WebDavSettingsScreen(
    onBackPress: () -> Unit,
    onNavigateToAddSource: () -> Unit,
    onNavigateToSourceDetail: (sourceId: String) -> Unit,
    viewModel: WebDavSettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val anyScanning = state.progress.values.any { it.isRunning }

    SettingsStandaloneScaffold(
        title = "WebDAV library",
        subtitle = "Play what your debrid account already holds."
    ) {
        SettingsDetailHeader(
            title = "Sources",
            subtitle = "Each source is scanned and matched to your metadata addon, so its " +
                "torrents appear as ordinary rows and as extra streams on titles you already have."
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = "Add source…",
                subtitle = "Real-Debrid, TorBox or any WebDAV server",
                leadingIcon = Icons.Default.CloudQueue,
                onClick = onNavigateToAddSource
            )
            SettingsActionRow(
                title = "Scan all sources",
                subtitle = if (anyScanning) {
                    "Scan in progress…"
                } else {
                    "Refresh the newest folders on every source"
                },
                leadingIcon = Icons.Default.Refresh,
                enabled = state.sources.isNotEmpty() && !anyScanning,
                onClick = { viewModel.scanAll() }
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
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.sources, key = { it.id }) { source ->
                            SourceRow(
                                source = source,
                                progress = state.progress[source.id],
                                counts = state.counts[source.id],
                                onClick = { onNavigateToSourceDetail(source.id) }
                            )
                        }
                    }
                    SettingsVerticalScrollIndicators(state = listState)
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: WebDavSource,
    progress: WebDavScanProgress?,
    counts: WebDavSourceCounts?,
    onClick: () -> Unit
) {
    SettingsActionRow(
        title = source.displayName,
        subtitle = "${source.provider.displayName} · ${statusTextFor(source, progress, counts)}",
        value = if (source.enabled) null else "Disabled",
        valueColor = NuvioTheme.colors.TextTertiary,
        leadingIcon = Icons.Default.CloudSync,
        titleTrailingIcon = if (progress?.phase == ScanPhase.FAILED) {
            Icons.Default.ErrorOutline
        } else {
            null
        },
        titleTrailingIconTint = NuvioTheme.colors.Error,
        onClick = onClick
    )
}

/** One line covering both what a scan is doing and what the source already holds. */
internal fun statusTextFor(
    source: WebDavSource,
    progress: WebDavScanProgress?,
    counts: WebDavSourceCounts?
): String = when (progress?.phase) {
    ScanPhase.LISTING -> "Listing folders…"
    ScanPhase.FOLDERS ->
        "Scanning ${progress.foldersDone}/${progress.foldersPlanned} · " +
            "${progress.filesFound} files"

    ScanPhase.MATCHING -> "Matching ${progress.matchesResolved}/${progress.foldersPlanned}"
    ScanPhase.FAILED -> "Failed: ${progress.errorMessage.orEmpty()}"
    else -> {
        val folders = counts?.folders ?: 0
        if (source.lastScanAt == null && folders == 0) {
            "Never scanned"
        } else {
            "$folders folders · ${counts?.files ?: 0} files · ${counts?.matched ?: 0} matched"
        }
    }
}
