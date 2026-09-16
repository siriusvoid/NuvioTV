@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.locallibrary.LocalLibraryManager
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsGroupNote
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.quantityStringResource
import java.text.DateFormat
import java.util.Date

@Composable
fun SourceDetailScreen(
    sourceId: String,
    onBackPress: () -> Unit,
    onNavigateToManualMatch: (sourceId: String) -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val config = state.sources.firstOrNull { it.id == sourceId }
    if (config == null) {
        SettingsStandaloneScaffold(title = stringResource(R.string.library_source), subtitle = "") {
            Text(
                text = stringResource(R.string.library_source_not_found),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        return
    }

    val progress = state.progress[sourceId]
    // MEDIUM date, SHORT time: the locale's wording without the seconds the default adds.
    val lastScan = config.lastScanAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: stringResource(R.string.library_never)
    val kindLabel = stringResource(viewModel.kindLabel(config.kind))

    SettingsStandaloneScaffold(
        title = config.displayName,
        subtitle = kindLabel
    ) {
        SettingsDetailHeader(
            title = config.displayName,
            subtitle = "$kindLabel · ${stringResource(R.string.library_last_scan, lastScan)} · " +
                quantityStringResource(R.plurals.local_library_items_indexed, config.itemCount)
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsToggleRow(
                title = stringResource(R.string.library_enabled),
                subtitle = stringResource(R.string.local_library_enabled_sub),
                checked = config.enabled,
                onToggle = { viewModel.setEnabled(sourceId, !config.enabled) }
            )
        }

        // Only shown while a scan is running or failed; idle repeats the header's count.
        val activeProgress = progress?.takeIf { it !is LocalLibraryManager.ScanProgress.Idle }
        if (activeProgress != null) {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.library_scan_status)
            ) {
                SettingsGroupNote(text = formatProgress(activeProgress))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Button(
                onClick = { viewModel.rescan(sourceId) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.library_rescan_now))
            }
            Button(
                onClick = { onNavigateToManualMatch(sourceId) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.local_library_manual_match))
            }
            Button(
                onClick = { viewModel.removeSource(sourceId) { onBackPress() } },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.Error
                )
            ) {
                Text(stringResource(R.string.library_source_remove))
            }
        }
    }
}

@Composable
private fun formatProgress(progress: LocalLibraryManager.ScanProgress): String =
    when (progress) {
        is LocalLibraryManager.ScanProgress.Idle -> stringResource(
            R.string.local_library_idle,
            quantityStringResource(R.plurals.local_library_items, progress.itemCount)
        )
        is LocalLibraryManager.ScanProgress.Scanning ->
            stringResource(R.string.local_library_scanning_found_so_far, progress.itemsFound)
        is LocalLibraryManager.ScanProgress.Matching ->
            stringResource(R.string.library_scan_matching, progress.matched, progress.total)
        is LocalLibraryManager.ScanProgress.Failed -> stringResource(R.string.library_scan_failed, progress.reason)
    }
