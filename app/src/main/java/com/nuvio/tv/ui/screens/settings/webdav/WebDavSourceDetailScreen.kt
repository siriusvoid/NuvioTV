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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.webdav.WebDavSource
import com.nuvio.tv.ui.screens.settings.SettingsChoiceChip
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsGroupNote
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.screens.settings.settingsOptionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.quantityStringResource
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
    val counts = state.counts[sourceId]
    val running = progress?.isRunning == true

    // MEDIUM date, SHORT time: the locale's wording without the seconds the default adds.
    val lastScan = source.lastScanAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: stringResource(R.string.library_never)
    val providerLabel = source.provider.label()

    SettingsStandaloneScaffold(
        title = source.displayName,
        subtitle = providerLabel
    ) {
        SettingsDetailHeader(
            title = source.displayName,
            subtitle = "$providerLabel · ${stringResource(R.string.library_last_scan, lastScan)} · " +
                quantityStringResource(R.plurals.webdav_folders_indexed, counts?.folders ?: 0)
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsToggleRow(
                title = stringResource(R.string.library_enabled),
                subtitle = stringResource(R.string.webdav_enabled_sub),
                checked = source.enabled,
                onToggle = { viewModel.setEnabled(sourceId, !source.enabled) }
            )
        }

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.webdav_scan_window),
            subtitle = stringResource(R.string.webdav_scan_window_desc)
        ) {
            WindowSizeRow(
                source = source,
                enabled = !running,
                onChange = { viewModel.setWindowSize(sourceId, it) }
            )
        }

        // Only shown while a scan runs or failed; idle repeats the header.
        if (running || progress?.errorMessage != null) {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.library_scan_status)
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
                Text(stringResource(if (running) R.string.webdav_scanning else R.string.webdav_scan_now))
            }
            Button(
                onClick = { onNavigateToReview(sourceId) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.webdav_review_matches))
            }
            Button(
                onClick = { viewModel.rebuild(sourceId) },
                enabled = !running,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.webdav_rebuild_index))
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

/** A stepper: the window is a small round number, and plus/minus beats the on-screen keyboard. */
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
            text = quantityStringResource(R.plurals.library_folders_count, source.windowSize),
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
