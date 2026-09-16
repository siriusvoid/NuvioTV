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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
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
import com.nuvio.tv.ui.util.quantityStringResource

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
        SettingsStandaloneScaffold(title = stringResource(R.string.webdav_fix_match), subtitle = "") {
            SettingsDetailHeader(
                title = stringResource(R.string.webdav_fix_match),
                subtitle = stringResource(R.string.webdav_fix_match_gone)
            )
        }
        return
    }

    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()

    // Seeded from the folder name the matcher searched with; usually a word or two needs cutting.
    var query by remember(row.folderKey) { mutableStateOf(row.match?.title ?: row.folderName) }
    var season by remember(row.folderKey) { mutableStateOf(row.match?.season) }
    var treatAsMovie by remember(row.folderKey) {
        mutableStateOf(row.match?.contentType == WebDavMatch.CONTENT_TYPE_MOVIE)
    }

    // Search on arrival: the first search costs a round trip either way.
    LaunchedEffect(row.folderKey) {
        if (query.isNotBlank()) viewModel.search(query)
    }

    val firstResultFocusRequester = remember { FocusRequester() }
    LaunchedEffect(results.firstOrNull()?.id) {
        if (results.isNotEmpty()) runCatching { firstResultFocusRequester.requestFocus() }
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.webdav_fix_match),
        subtitle = row.folderName
    ) {
        SettingsDetailHeader(
            title = row.match?.displayName ?: stringResource(R.string.library_unmatched),
            subtitle = listOfNotNull(
                row.folderName,
                quantityStringResource(R.plurals.library_files, row.fileCount),
                row.match?.placementStep?.label()
            ).joinToString(" · ")
        )

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.webdav_search)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                SettingsTextRow(
                    label = stringResource(R.string.webdav_title),
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
                        label = stringResource(if (searching) R.string.webdav_searching else R.string.webdav_search),
                        selected = false,
                        onClick = { viewModel.search(query) },
                        modifier = Modifier.focusRequester(firstChip)
                    )
                    SettingsToggleChip(
                        label = stringResource(R.string.type_series),
                        selected = !treatAsMovie,
                        onClick = { treatAsMovie = false }
                    )
                    SettingsToggleChip(
                        label = stringResource(R.string.type_movie),
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
            title = stringResource(R.string.library_candidates),
            subtitle = stringResource(R.string.webdav_candidates_desc)
        ) {
            if (results.isEmpty()) {
                SettingsGroupNote(
                    text = stringResource(if (searching) R.string.webdav_searching else R.string.webdav_no_candidates)
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
                            val type = animeTypeLabel(hit.subtype)
                            val episodes = hit.episodeCount?.let {
                                quantityStringResource(R.plurals.webdav_episodes, it)
                            }
                            SettingsActionRow(
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstResultFocusRequester)
                                } else {
                                    Modifier
                                },
                                title = hit.title,
                                subtitle = buildString {
                                    append(type)
                                    episodes?.let { append(" · ").append(it) }
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
                Text(stringResource(R.string.webdav_match_again))
            }
            Button(
                onClick = { viewModel.toggleExcluded(row) },
                enabled = row.match != null,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(
                    stringResource(
                        if (row.match?.excluded == true) R.string.webdav_include_folder
                        else R.string.webdav_exclude_folder
                    )
                )
            }
        }
    }
}

/** Kitsu and MyAnimeList type names; values not listed pass through as sent. */
@Composable
private fun animeTypeLabel(subtype: String?): String = when (subtype?.lowercase()) {
    null, "tv" -> stringResource(R.string.webdav_anime_type_tv)
    "movie" -> stringResource(R.string.type_movie)
    "special" -> stringResource(R.string.webdav_anime_type_special)
    "tv special" -> stringResource(R.string.webdav_anime_type_tv_special)
    "music" -> stringResource(R.string.webdav_anime_type_music)
    else -> subtype.orEmpty()
}

/** The folder's season; unset keeps the matcher's answer. */
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
            text = stringResource(R.string.webdav_season),
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
            text = season?.toString() ?: stringResource(R.string.webdav_season_auto),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        SettingsToggleChip(
            label = "+",
            selected = false,
            onClick = { onChange((season ?: 0) + 1) }
        )
        SettingsToggleChip(
            label = stringResource(R.string.webdav_season_auto),
            selected = season == null,
            onClick = { onChange(null) }
        )
    }
}
