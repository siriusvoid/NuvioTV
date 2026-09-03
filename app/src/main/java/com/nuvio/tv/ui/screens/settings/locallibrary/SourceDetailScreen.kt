@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.locallibrary.LocalLibraryManager
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsGroupNote
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
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
        SettingsStandaloneScaffold(title = "Source", subtitle = "") {
            Text(
                text = "Source not found.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        return
    }

    val progress = state.progress[sourceId]
    // MEDIUM date keeps the locale's usual "3 Sep 2026" wording; SHORT time drops
    // the seconds the no-arg default would add.
    val lastScan = config.lastScanAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: "Never"

    SettingsStandaloneScaffold(
        title = config.displayName,
        subtitle = viewModel.kindLabel(config.kind)
    ) {
        SettingsDetailHeader(
            title = config.displayName,
            subtitle = "${viewModel.kindLabel(config.kind)} · Last scan: $lastScan · " +
                "${config.itemCount} items indexed"
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsToggleRow(
                title = "Enabled",
                subtitle = "Show this source's content in catalogs and search.",
                checked = config.enabled,
                onToggle = { viewModel.setEnabled(sourceId, !config.enabled) }
            )
        }

        // Idle repeats the item count the header already carries, so the card only
        // earns its place while a scan is actually doing something, or has failed.
        val activeProgress = progress?.takeIf { it !is LocalLibraryManager.ScanProgress.Idle }
        if (activeProgress != null) {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Scan status"
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
                Text("Rescan now")
            }
            Button(
                onClick = { onNavigateToManualMatch(sourceId) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text("Manual match")
            }
            Button(
                onClick = { viewModel.removeSource(sourceId) { onBackPress() } },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.Error
                )
            ) {
                Text("Remove source")
            }
        }
    }
}

private fun formatProgress(progress: LocalLibraryManager.ScanProgress): String =
    when (progress) {
        is LocalLibraryManager.ScanProgress.Idle -> "Idle (${progress.itemCount} items)"
        is LocalLibraryManager.ScanProgress.Scanning -> "Scanning… ${progress.itemsFound} found so far"
        is LocalLibraryManager.ScanProgress.Matching -> "Matching ${progress.matched}/${progress.total}"
        is LocalLibraryManager.ScanProgress.Failed -> "Failed: ${progress.reason}"
    }
