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
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitleFile
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * One imported pack: whether it survives being watched, and what each of its
 * files was matched to.
 */
@Composable
fun ImportedSubtitlePackScreen(
    packId: String,
    onBackPress: () -> Unit,
    viewModel: ImportedSubtitlesViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val packs by viewModel.packs.collectAsStateWithLifecycle()
    val pack = packs.firstOrNull { it.id == packId }

    // Deleting the pack — or watching the show through, which removes it on its own —
    // empties this screen, so it steps back. Only after the pack has actually been
    // seen: the list arrives asynchronously, and a cold start begins with none.
    var packWasPresent by remember { mutableStateOf(false) }
    LaunchedEffect(pack) {
        if (pack != null) packWasPresent = true else if (packWasPresent) onBackPress()
    }

    if (pack == null) {
        SettingsStandaloneScaffold(title = "Imported subtitles", subtitle = "") {
            Text(
                text = "This pack is no longer available.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        return
    }

    SettingsStandaloneScaffold(
        title = pack.showName,
        subtitle = "Imported subtitles"
    ) {
        SettingsDetailHeader(
            title = pack.showName,
            subtitle = packSummary(pack)
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsToggleRow(
                title = "Keep after watching",
                subtitle = "Otherwise the files go once every episode they cover is watched.",
                checked = pack.keepAfterWatching,
                onToggle = { viewModel.setKeepAfterWatching(pack.id, !pack.keepAfterWatching) }
            )
        }

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = "Files"
        ) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    items(pack.files, key = { it.relativePath }) { file ->
                        FileRow(file)
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Button(
                onClick = { viewModel.deletePack(pack.id) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.Error
                )
            ) {
                Text("Delete pack")
            }
        }
    }
}

@Composable
private fun FileRow(file: ImportedSubtitleFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = file.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = when {
                file.season != null && file.episode != null -> "S${file.season} E${file.episode}"
                file.isMatched -> "Film"
                else -> "Not matched"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (file.isMatched) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.Error
        )
    }
}
