@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.webdav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.webdav.WebDavSource
import com.nuvio.tv.ui.screens.settings.SettingsChoiceChip
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsGroupNote
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.screens.settings.settingsOptionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date

@Composable
internal fun WebDavSourceDetailScreen(
    sourceId: String,
    onBackPress: () -> Unit,
    onNavigateToReview: (sourceId: String) -> Unit,
    viewModel: WebDavSettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val source = state.sources.firstOrNull { it.id == sourceId }
    if (source == null) {
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
    val counts = state.counts[sourceId]
    val running = progress?.isRunning == true

    // MEDIUM date keeps the locale's usual "3 Sep 2026" wording; SHORT time drops
    // the seconds the no-arg default would add.
    val lastScan = source.lastScanAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: "Never"

    SettingsStandaloneScaffold(
        title = source.displayName,
        subtitle = source.provider.displayName
    ) {
        SettingsDetailHeader(
            title = source.displayName,
            subtitle = "${source.provider.displayName} · Last scan: $lastScan · " +
                "${counts?.folders ?: 0} folders indexed"
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsToggleRow(
                title = "Enabled",
                subtitle = "Show this source's content in catalogs and offer its files as streams.",
                checked = source.enabled,
                onToggle = { viewModel.setEnabled(sourceId, !source.enabled) }
            )
        }

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = "Scan window",
            subtitle = "How many of the newest folders each scan refreshes. Older folders stay " +
                "indexed; a larger window only costs time on the server."
        ) {
            WindowSizeRow(
                source = source,
                enabled = !running,
                onChange = { viewModel.setWindowSize(sourceId, it) }
            )
        }

        // Idle repeats what the header already says, so the card only earns its
        // place while a scan is doing something, or has failed.
        if (running || progress?.errorMessage != null) {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Scan status"
            ) {
                SettingsGroupNote(text = statusTextFor(source, progress, counts))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Button(
                onClick = { viewModel.scan(sourceId) },
                enabled = !running,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(if (running) "Scanning…" else "Scan now")
            }
            Button(
                onClick = { onNavigateToReview(sourceId) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text("Review matches")
            }
            Button(
                onClick = { viewModel.rebuild(sourceId) },
                enabled = !running,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text("Rebuild index")
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

/**
 * A stepper rather than a free-text field: the window is a round number in a small
 * range, and on a remote a plus/minus pair beats opening the on-screen keyboard.
 */
@Composable
private fun WindowSizeRow(
    source: WebDavSource,
    enabled: Boolean,
    onChange: (Int) -> Unit
) {
    val decrease = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsOptionRow(decrease),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsChoiceChip(
            label = "−10",
            selected = false,
            onClick = { if (enabled) onChange(source.windowSize - STEP) },
            modifier = Modifier.focusRequester(decrease)
        )
        Text(
            text = "${source.windowSize} folders",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        SettingsChoiceChip(
            label = "+10",
            selected = false,
            onClick = { if (enabled) onChange(source.windowSize + STEP) }
        )
    }
}

private const val STEP = 10
