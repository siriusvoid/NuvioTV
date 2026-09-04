@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.subtitles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsGroupNote
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date

/**
 * One subtitle folder: what scanning it matched, and what it could not place.
 */
@Composable
fun SubtitleFolderDetailScreen(
    sourceId: String,
    onBackPress: () -> Unit,
    viewModel: SubtitleFoldersViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val packs by viewModel.packs.collectAsStateWithLifecycle()
    val unmatched by viewModel.unmatched.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val source = sources.firstOrNull { it.id == sourceId }

    // Removing the folder empties this screen, so it steps back — but only once the
    // source has actually been seen, since the index arrives asynchronously.
    var sourceWasPresent by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        if (source != null) sourceWasPresent = true else if (sourceWasPresent) onBackPress()
    }

    if (source == null) {
        SettingsStandaloneScaffold(title = "Subtitle folders", subtitle = "") {
            Text(
                text = "This folder is no longer added.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        return
    }

    val sourcePacks = packs.filter { it.sourceId == sourceId }
    val sourceUnmatched = unmatched.filter { it.sourceId == sourceId }
    // MEDIUM date keeps the locale's usual "3 Sep 2026" wording; SHORT time drops
    // the seconds the no-arg default would add.
    val lastScan = source.lastScanAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: "Never"

    SettingsStandaloneScaffold(
        title = source.displayName,
        subtitle = source.path
    ) {
        SettingsDetailHeader(
            title = source.displayName,
            subtitle = "Last scan: $lastScan · ${sourcePacks.size} release(s) · " +
                "${sourcePacks.sumOf { it.files.size }} file(s)" +
                if (sourceUnmatched.isEmpty()) "" else " · ${sourceUnmatched.size} unmatched"
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsToggleRow(
                title = "Enabled",
                subtitle = "Offer these subtitles in the player.",
                checked = source.enabled,
                onToggle = { viewModel.setEnabled(sourceId, !source.enabled) }
            )
            SettingsGroupNote(
                text = scanStatusText(
                    progress = progress[sourceId],
                    source = source,
                    packs = sourcePacks.size,
                    files = sourcePacks.sumOf { it.files.size }
                )
            )
        }

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = "Matched releases"
        ) {
            if (sourcePacks.isEmpty() && sourceUnmatched.isEmpty()) {
                SettingsGroupNote(text = "Nothing found here yet. Rescan once the folder has files in it.")
            } else {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                    ) {
                        items(sourcePacks, key = { it.id }) { pack -> PackRow(pack) }
                        items(sourceUnmatched, key = { it.folderPath + it.releaseTitle }) { entry ->
                            ReleaseRow(
                                title = entry.releaseTitle.ifBlank { entry.folderPath },
                                detail = "${entry.fileCount} file(s) · not matched",
                                isError = true
                            )
                        }
                    }
                    SettingsVerticalScrollIndicators(state = listState)
                }
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
                onClick = { viewModel.removeSource(sourceId) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.Error
                )
            ) {
                Text("Remove folder")
            }
        }

        SettingsGroupNote(
            text = "Removing a folder only drops Nuvio's index. Your subtitle files are never " +
                "copied, moved or deleted."
        )
    }
}

@Composable
private fun PackRow(pack: ImportedSubtitlePack) {
    val season = pack.files.firstNotNullOfOrNull { it.season }
    ReleaseRow(
        title = pack.showName,
        detail = listOfNotNull(
            season?.let { "Season $it" },
            "${pack.matchedCount}/${pack.files.size} matched"
        ).joinToString(" · "),
        isError = pack.matchedCount == 0
    )
}

@Composable
private fun ReleaseRow(title: String, detail: String, isError: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) NuvioTheme.colors.Error else NuvioTheme.colors.TextTertiary
        )
    }
}
