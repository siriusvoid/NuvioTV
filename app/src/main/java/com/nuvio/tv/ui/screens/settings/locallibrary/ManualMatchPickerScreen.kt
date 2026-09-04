@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleChip
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.screens.settings.settingsOptionRow
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun ManualMatchPickerScreen(
    sourceId: String,
    itemKey: String,
    onBackPress: () -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    LaunchedEffect(sourceId, itemKey) {
        viewModel.loadUnmatched(sourceId)
    }
    val unmatched by viewModel.unmatched.collectAsStateWithLifecycle()
    val item: ScannedItem? = unmatched.firstOrNull { it.itemKey == itemKey }

    // Default to the freshly parsed type (Series for "S01E01" files), but let the
    // user flip it — TMDB movie vs TV search returns very different results, and
    // a wrong type is exactly why anime episodes used to resolve to movies.
    var selectedType by remember(item?.itemKey) {
        mutableStateOf(item?.let { viewModel.parsedContentType(it) } ?: ContentType.SERIES)
    }
    // Default to matching the whole show — anime folders hold every episode, so
    // one pick fixes them all instead of matching each file.
    var applyToFolder by remember(item?.itemKey) { mutableStateOf(true) }

    LaunchedEffect(item, selectedType) {
        item?.let { viewModel.loadCandidates(it, selectedType) }
    }
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()

    // Picking a match is the job here; the type chips are a correction you reach
    // for only when the results look wrong. So start on the results.
    val firstCandidateFocusRequester = remember { FocusRequester() }
    LaunchedEffect(candidates.firstOrNull()?.id) {
        if (candidates.isNotEmpty()) {
            runCatching { firstCandidateFocusRequester.requestFocus() }
        }
    }

    if (item == null) {
        SettingsStandaloneScaffold(title = "Pick match", subtitle = "") {
            SettingsDetailHeader(
                title = "Pick match",
                subtitle = "This file is no longer waiting on a match."
            )
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Item no longer in unmatched list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        return
    }

    SettingsStandaloneScaffold(
        title = "Pick match",
        subtitle = item.fileName
    ) {
        SettingsDetailHeader(
            title = "Top TMDB results",
            subtitle = "${item.fileName} · selecting one stores a permanent override for this file."
        )

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = "Search as"
        ) {
            val firstTypeFocusRequester = remember { FocusRequester() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .settingsOptionRow(firstTypeFocusRequester),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                SettingsToggleChip(
                    label = "Series",
                    selected = selectedType == ContentType.SERIES,
                    onClick = { selectedType = ContentType.SERIES },
                    modifier = Modifier.focusRequester(firstTypeFocusRequester)
                )
                SettingsToggleChip(
                    label = "Movie",
                    selected = selectedType == ContentType.MOVIE,
                    onClick = { selectedType = ContentType.MOVIE }
                )
                if (selectedType == ContentType.SERIES) {
                    SettingsToggleChip(
                        label = "Whole folder",
                        selected = applyToFolder,
                        onClick = { applyToFolder = !applyToFolder }
                    )
                }
            }
        }

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = "Candidates"
        ) {
            if (candidates.isEmpty()) {
                Text(
                    text = "No candidates returned. Try the other type above, or rename the file with a clear title.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            } else {
                val candidateListState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = candidateListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        itemsIndexed(
                            candidates,
                            key = { _, candidate -> candidate.id }
                        ) { index, candidate ->
                            val name = candidate.title ?: candidate.name ?: "Untitled"
                            val year = (candidate.releaseDate ?: candidate.firstAirDate)?.take(4)
                            SettingsActionRow(
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstCandidateFocusRequester)
                                } else {
                                    Modifier
                                },
                                title = if (year != null) "$name ($year)" else name,
                                subtitle = candidate.overview
                                    ?.takeIf { it.isNotBlank() }
                                    ?.take(120),
                                onClick = {
                                    if (selectedType == ContentType.SERIES && applyToFolder) {
                                        viewModel.matchFolder(item, candidate, selectedType) {
                                            onBackPress()
                                        }
                                    } else {
                                        viewModel.pickCandidate(item, candidate, selectedType) {
                                            onBackPress()
                                        }
                                    }
                                }
                            )
                        }
                    }
                    SettingsVerticalScrollIndicators(state = candidateListState)
                }
            }
        }
    }
}
