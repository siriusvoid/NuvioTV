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
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsGroupNote
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The imported subtitle library: one row per pack, each opening the pack's own
 * page. Importing starts from a show's details page, so there is nothing to add
 * from here.
 */
@Composable
fun ImportedSubtitlesSettingsScreen(
    onBackPress: () -> Unit,
    onNavigateToPack: (packId: String) -> Unit,
    viewModel: ImportedSubtitlesViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val packs by viewModel.packs.collectAsStateWithLifecycle()

    SettingsStandaloneScaffold(
        title = "Imported subtitles",
        subtitle = "Subtitle files you added from this device."
    ) {
        SettingsDetailHeader(
            title = "Imported subtitles",
            subtitle = "Files are matched to episodes by their names and offered in the " +
                "player's subtitle menu, alongside the ones your addons find."
        )

        if (packs.isEmpty()) {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsGroupNote(
                    text = "Nothing imported yet. Open a show, then choose Subtitles on its " +
                        "details page to pick a folder."
                )
            }
            return@SettingsStandaloneScaffold
        }

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = "Packs"
        ) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(packs, key = { it.id }) { pack ->
                        SettingsActionRow(
                            title = pack.showName,
                            subtitle = packSummary(pack),
                            leadingIcon = Icons.Default.Subtitles,
                            onClick = { onNavigateToPack(pack.id) }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
}

internal fun packSummary(pack: ImportedSubtitlePack): String = listOfNotNull(
    pack.sourceName?.takeIf { it.isNotBlank() },
    "${pack.files.size} files, ${pack.matchedCount} matched"
).joinToString(" · ")
