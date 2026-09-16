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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.data.webdav.WebDavSourceCounts
import com.nuvio.tv.domain.model.webdav.PlacementStep
import com.nuvio.tv.domain.model.webdav.ScanPhase
import com.nuvio.tv.domain.model.webdav.WebDavProvider
import com.nuvio.tv.domain.model.webdav.WebDavScanProgress
import com.nuvio.tv.domain.model.webdav.WebDavSource
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.quantityStringResource

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
        title = stringResource(R.string.settings_webdav_title),
        subtitle = stringResource(R.string.webdav_screen_subtitle)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.library_sources),
            subtitle = stringResource(R.string.webdav_sources_desc)
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.library_source_add),
                subtitle = stringResource(R.string.webdav_add_source_sub),
                leadingIcon = Icons.Default.CloudQueue,
                onClick = onNavigateToAddSource
            )
            SettingsActionRow(
                title = stringResource(R.string.webdav_scan_all),
                subtitle = stringResource(
                    if (anyScanning) R.string.library_scanning_in_progress else R.string.webdav_scan_all_sub
                ),
                leadingIcon = Icons.Default.Refresh,
                enabled = state.sources.isNotEmpty() && !anyScanning,
                onClick = { viewModel.scanAll() }
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
        subtitle = "${source.provider.label()} · ${statusTextFor(source, progress, counts)}",
        value = if (source.enabled) null else stringResource(R.string.library_disabled),
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
@Composable
internal fun statusTextFor(
    source: WebDavSource,
    progress: WebDavScanProgress?,
    counts: WebDavSourceCounts?
): String = when (progress?.phase) {
    ScanPhase.LISTING -> stringResource(R.string.webdav_listing_folders)
    ScanPhase.FOLDERS -> stringResource(
        R.string.webdav_scanning_progress,
        progress.foldersDone,
        progress.foldersPlanned,
        quantityStringResource(R.plurals.library_files, progress.filesFound)
    )

    ScanPhase.MATCHING ->
        stringResource(R.string.library_scan_matching, progress.matchesResolved, progress.foldersPlanned)
    ScanPhase.FAILED -> stringResource(R.string.library_scan_failed, progress.errorMessage.orEmpty())
    else -> {
        val folders = counts?.folders ?: 0
        if (source.lastScanAt == null && folders == 0) {
            stringResource(R.string.webdav_never_scanned)
        } else {
            listOf(
                quantityStringResource(R.plurals.library_folders_count, folders),
                quantityStringResource(R.plurals.library_files, counts?.files ?: 0),
                stringResource(R.string.library_matched_count, counts?.matched ?: 0)
            ).joinToString(" · ")
        }
    }
}

@Composable
internal fun WebDavProvider.label(): String =
    if (this == WebDavProvider.CUSTOM) stringResource(R.string.webdav_provider_custom) else displayName

@Composable
internal fun PlacementStep.label(): String = stringResource(
    when (this) {
        PlacementStep.EXPLICIT_SEASON_EPISODE -> R.string.webdav_placement_filename
        PlacementStep.MAPPER_SEASON -> R.string.webdav_placement_mapper_season
        PlacementStep.EPISODE_COUNT_FIT -> R.string.webdav_placement_episode_count
        PlacementStep.AIR_DATE_ANCHOR -> R.string.webdav_placement_air_date
        PlacementStep.FLATTENED_ABSOLUTE -> R.string.webdav_placement_flattened
        PlacementStep.MANUAL -> R.string.webdav_placement_manual
        PlacementStep.UNRESOLVED -> R.string.webdav_placement_unresolved
    }
)
