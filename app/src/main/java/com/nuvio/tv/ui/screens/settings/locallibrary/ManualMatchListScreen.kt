@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun ManualMatchListScreen(
    sourceId: String,
    onBackPress: () -> Unit,
    onNavigateToPicker: (sourceId: String, itemKey: String) -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    LaunchedEffect(sourceId) { viewModel.loadUnmatched(sourceId) }
    // The picker has its own view model, so reload on resume to show a pick right away.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sourceId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadUnmatched(sourceId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val unmatched by viewModel.unmatched.collectAsStateWithLifecycle()

    // Start on the list, the only thing to do here.
    val firstItemFocusRequester = remember { FocusRequester() }
    LaunchedEffect(unmatched.isEmpty()) {
        if (unmatched.isNotEmpty()) {
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.local_library_manual_match),
        subtitle = stringResource(R.string.local_library_manual_match_desc)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.local_library_unmatched_count, unmatched.size),
            subtitle = stringResource(R.string.local_library_unmatched_desc)
        )

        if (unmatched.isEmpty()) {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.local_library_all_matched),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        } else {
            SettingsGroupCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val unmatchedListState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = unmatchedListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        itemsIndexed(
                            unmatched,
                            key = { _, item -> item.itemKey }
                        ) { index, item ->
                            SettingsActionRow(
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstItemFocusRequester)
                                } else {
                                    Modifier
                                },
                                title = item.fileName,
                                subtitle = item.parsedTitle?.let { parsed ->
                                    listOfNotNull(
                                        parsed,
                                        item.parsedYear?.toString(),
                                        item.parsedSeason?.let { season ->
                                            item.parsedEpisode?.let { episode ->
                                                stringResource(R.string.season_episode_format, season, episode)
                                            } ?: stringResource(R.string.ratings_season_label, season)
                                        }
                                    ).joinToString(" · ")
                                },
                                leadingIcon = Icons.Default.Movie,
                                onClick = { onNavigateToPicker(item.sourceId, item.itemKey) }
                            )
                        }
                    }
                    SettingsVerticalScrollIndicators(state = unmatchedListState)
                }
            }
        }
    }
}
