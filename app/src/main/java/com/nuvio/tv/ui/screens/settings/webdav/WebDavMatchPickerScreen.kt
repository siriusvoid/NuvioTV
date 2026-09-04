@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.webdav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.nuvio.tv.domain.model.webdav.WebDavMatch
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsGroupNote
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsTextRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleChip
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.screens.settings.settingsOptionRow
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun WebDavMatchPickerScreen(
    sourceId: String,
    folderKey: String,
    onBackPress: () -> Unit,
    viewModel: WebDavSettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    LaunchedEffect(sourceId) { viewModel.loadReviewRows(sourceId) }
    val rows by viewModel.reviewRows.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.folderKey == folderKey }

    if (row == null) {
        SettingsStandaloneScaffold(title = "Fix match", subtitle = "") {
            SettingsDetailHeader(
                title = "Fix match",
                subtitle = "This folder is no longer in the index."
            )
        }
        return
    }

    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()

    // Seeded below the guard above, so the row is always in hand: the rows arrive a
    // frame after this screen does, and state seeded before then would hold blanks.
    // The folder name is what the automatic matcher searched with, so it is the
    // right starting point for a correction — usually a word or two needs cutting.
    var query by remember(row.folderKey) { mutableStateOf(row.match?.title ?: row.folderName) }
    var season by remember(row.folderKey) { mutableStateOf(row.match?.season) }
    var treatAsMovie by remember(row.folderKey) {
        mutableStateOf(row.match?.contentType == WebDavMatch.CONTENT_TYPE_MOVIE)
    }

    // The first search costs a round trip whichever way it is started, so run it on
    // arrival rather than making the user press Search to see the obvious guess.
    LaunchedEffect(row.folderKey) {
        if (query.isNotBlank()) viewModel.search(query)
    }

    val firstResultFocusRequester = remember { FocusRequester() }
    LaunchedEffect(results.firstOrNull()?.id) {
        if (results.isNotEmpty()) runCatching { firstResultFocusRequester.requestFocus() }
    }

    SettingsStandaloneScaffold(
        title = "Fix match",
        subtitle = row.folderName
    ) {
        SettingsDetailHeader(
            title = row.match?.displayName ?: "Unmatched",
            subtitle = "${row.folderName} · ${row.fileCount} files" +
                (row.match?.let { " · ${it.placementStep.label}" } ?: "")
        )

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = "Search"
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                SettingsTextRow(
                    label = "Title",
                    value = query,
                    onValueChange = { query = it }
                )
                val firstChip = remember { FocusRequester() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsOptionRow(firstChip),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsToggleChip(
                        label = if (searching) "Searching…" else "Search",
                        selected = false,
                        onClick = { viewModel.search(query) },
                        modifier = Modifier.focusRequester(firstChip)
                    )
                    SettingsToggleChip(
                        label = "Series",
                        selected = !treatAsMovie,
                        onClick = { treatAsMovie = false }
                    )
                    SettingsToggleChip(
                        label = "Movie",
                        selected = treatAsMovie,
                        onClick = { treatAsMovie = true }
                    )
                }
                if (!treatAsMovie) {
                    SeasonRow(season = season, onChange = { season = it })
                }
            }
        }

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = "Candidates",
            subtitle = "Picking one stores a permanent override; rescans never overwrite it."
        ) {
            if (results.isEmpty()) {
                SettingsGroupNote(
                    text = if (searching) {
                        "Searching…"
                    } else {
                        "No candidates. Try a shorter title — release tags and group names " +
                            "confuse the anime databases."
                    }
                )
            } else {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        itemsIndexed(
                            results,
                            key = { _, hit -> "${hit.source}:${hit.id}" }
                        ) { index, hit ->
                            SettingsActionRow(
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstResultFocusRequester)
                                } else {
                                    Modifier
                                },
                                title = hit.title,
                                subtitle = buildString {
                                    append(hit.subtype ?: "TV")
                                    hit.episodeCount?.let { append(" · ").append(it).append(" episodes") }
                                    hit.alternativeTitles.firstOrNull()
                                        ?.takeIf { it != hit.title }
                                        ?.let { append(" · ").append(it) }
                                },
                                onClick = {
                                    viewModel.applyOverride(
                                        sourceId = sourceId,
                                        folderKey = folderKey,
                                        hit = hit,
                                        season = season,
                                        treatAsMovie = treatAsMovie
                                    ) { onBackPress() }
                                }
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
                onClick = { viewModel.rematch(row) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text("Match again")
            }
            Button(
                onClick = { viewModel.toggleExcluded(row) },
                enabled = row.match != null,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(if (row.match?.excluded == true) "Include folder" else "Exclude folder")
            }
        }
    }
}

/**
 * Which season the folder's episodes belong to. Left unset the matcher's own
 * answer stands, which is right whenever it found one.
 */
@Composable
private fun SeasonRow(season: Int?, onChange: (Int?) -> Unit) {
    val decrease = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsOptionRow(decrease),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Season",
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextSecondary
        )
        SettingsToggleChip(
            label = "−",
            selected = false,
            onClick = { onChange(((season ?: 1) - 1).coerceAtLeast(0)) },
            modifier = Modifier.focusRequester(decrease)
        )
        Text(
            text = season?.toString() ?: "Auto",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        SettingsToggleChip(
            label = "+",
            selected = false,
            onClick = { onChange((season ?: 0) + 1) }
        )
        SettingsToggleChip(
            label = "Auto",
            selected = season == null,
            onClick = { onChange(null) }
        )
    }
}
