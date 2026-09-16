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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
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

    // Default to the parsed type, but let the user flip it: a wrong type resolves anime episodes to movies.
    var selectedType by remember(item?.itemKey) {
        mutableStateOf(item?.let { viewModel.parsedContentType(it) } ?: ContentType.SERIES)
    }
    // Match the whole show by default: anime folders hold every episode, so one pick fixes them all.
    var applyToFolder by remember(item?.itemKey) { mutableStateOf(true) }

    LaunchedEffect(item, selectedType) {
        item?.let { viewModel.loadCandidates(it, selectedType) }
    }
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()

    // Start on the results; the type chips are only for correcting bad results.
    val firstCandidateFocusRequester = remember { FocusRequester() }
    LaunchedEffect(candidates.firstOrNull()?.id) {
        if (candidates.isNotEmpty()) {
            runCatching { firstCandidateFocusRequester.requestFocus() }
        }
    }

    if (item == null) {
        SettingsStandaloneScaffold(title = stringResource(R.string.local_library_pick_match), subtitle = "") {
            SettingsDetailHeader(
                title = stringResource(R.string.local_library_pick_match),
                subtitle = stringResource(R.string.local_library_pick_match_gone)
            )
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.local_library_pick_match_gone_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        return
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.local_library_pick_match),
        subtitle = item.fileName
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.local_library_top_results),
            subtitle = stringResource(R.string.local_library_top_results_desc, item.fileName)
        )

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.local_library_search_as)
        ) {
            val firstTypeFocusRequester = remember { FocusRequester() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .settingsOptionRow(firstTypeFocusRequester),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                SettingsToggleChip(
                    label = stringResource(R.string.type_series),
                    selected = selectedType == ContentType.SERIES,
                    onClick = { selectedType = ContentType.SERIES },
                    modifier = Modifier.focusRequester(firstTypeFocusRequester)
                )
                SettingsToggleChip(
                    label = stringResource(R.string.type_movie),
                    selected = selectedType == ContentType.MOVIE,
                    onClick = { selectedType = ContentType.MOVIE }
                )
                if (selectedType == ContentType.SERIES) {
                    SettingsToggleChip(
                        label = stringResource(R.string.local_library_whole_folder),
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
            title = stringResource(R.string.library_candidates)
        ) {
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.local_library_no_candidates),
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
                            val name = candidate.title ?: candidate.name
                                ?: stringResource(R.string.local_library_untitled)
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
