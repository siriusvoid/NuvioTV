@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.subtitles

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.subtitles.SubtitleScanProgress
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsGroupNote
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.theme.NuvioTheme

/** One row per added folder; scanning matches every release inside, so a parent folder is added once. */
@Composable
fun SubtitleFoldersScreen(
    onBackPress: () -> Unit,
    onNavigateToAddFolder: () -> Unit,
    onNavigateToFolder: (sourceId: String) -> Unit,
    viewModel: SubtitleFoldersViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val packs by viewModel.packs.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    val anyScanning = progress.values.any {
        it is SubtitleScanProgress.Scanning || it is SubtitleScanProgress.Matching
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.sub_imported),
        subtitle = stringResource(R.string.subtitle_folders_screen_subtitle)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.library_folders),
            subtitle = stringResource(R.string.subtitle_folders_desc)
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.subtitle_folders_add),
                subtitle = stringResource(R.string.subtitle_folders_add_sub),
                leadingIcon = Icons.Default.CreateNewFolder,
                onClick = onNavigateToAddFolder
            )
            SettingsActionRow(
                title = stringResource(R.string.subtitle_folders_rescan_all),
                subtitle = stringResource(
                    if (anyScanning) R.string.library_scanning_in_progress
                    else R.string.subtitle_folders_rescan_all_sub
                ),
                leadingIcon = Icons.Default.Refresh,
                enabled = sources.isNotEmpty() && !anyScanning,
                onClick = { viewModel.rescanAll() }
            )
        }

        if (sources.isEmpty()) {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsGroupNote(
                    text = stringResource(R.string.subtitle_folders_empty)
                )
            }
            return@SettingsStandaloneScaffold
        }

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = stringResource(R.string.subtitle_folders_added)
        ) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sources, key = { it.id }) { source ->
                        val sourcePacks = packs.filter { it.sourceId == source.id }
                        val sourceProgress = progress[source.id]
                        SettingsActionRow(
                            title = source.displayName,
                            subtitle = scanStatusText(
                                progress = sourceProgress,
                                source = source,
                                packs = sourcePacks.size,
                                files = sourcePacks.sumOf { it.files.size }
                            ),
                            value = if (source.enabled) null else stringResource(R.string.library_disabled),
                            valueColor = NuvioTheme.colors.TextTertiary,
                            leadingIcon = Icons.Default.Subtitles,
                            titleTrailingIcon = if (sourceProgress is SubtitleScanProgress.Failed) {
                                Icons.Default.ErrorOutline
                            } else {
                                null
                            },
                            titleTrailingIconTint = NuvioTheme.colors.Error,
                            onClick = { onNavigateToFolder(source.id) }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
}
