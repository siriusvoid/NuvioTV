@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.webdav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.nuvio.tv.domain.model.webdav.PlacementStep
import com.nuvio.tv.domain.model.webdav.WebDavReviewRow
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsGroupNote
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.quantityStringResource

/** Below this, a resolved match is still surfaced for review: a misplaced episode corrupts watch progress. */
private const val LOW_CONFIDENCE = 0.75f

@Composable
internal fun WebDavReviewScreen(
    sourceId: String,
    onBackPress: () -> Unit,
    onNavigateToPicker: (sourceId: String, folderKey: String) -> Unit,
    viewModel: WebDavSettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    LaunchedEffect(sourceId) { viewModel.loadReviewRows(sourceId) }
    // The picker has its own view model, so reload on resume to show a pick right away.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sourceId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadReviewRows(sourceId)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val rows by viewModel.reviewRows.collectAsStateWithLifecycle()
    val buckets = remember(rows) { bucket(rows) }

    val firstRowFocusRequester = remember { FocusRequester() }
    LaunchedEffect(rows.isEmpty()) {
        if (rows.isNotEmpty()) runCatching { firstRowFocusRequester.requestFocus() }
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.webdav_review_matches),
        subtitle = stringResource(R.string.webdav_review_desc)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.webdav_review_folders_count, rows.size),
            subtitle = stringResource(R.string.webdav_review_folders_desc)
        )

        if (rows.isEmpty()) {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsGroupNote(text = stringResource(R.string.webdav_review_empty))
            }
            return@SettingsStandaloneScaffold
        }

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            // Named up front: item lambdas run at composition, so a counter there doesn't track list order.
            val firstRowKey = buckets.firstNotNullOfOrNull { it.second.firstOrNull() }?.folderKey
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    buckets.forEach { (heading, bucketRows) ->
                        if (bucketRows.isEmpty()) return@forEach
                        item(key = "heading-$heading") {
                            Text(
                                text = "${stringResource(heading).uppercase()} (${bucketRows.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        items(
                            count = bucketRows.size,
                            key = { bucketRows[it].folderKey }
                        ) { index ->
                            val row = bucketRows[index]
                            ReviewRow(
                                row = row,
                                modifier = if (row.folderKey == firstRowKey) {
                                    Modifier.focusRequester(firstRowFocusRequester)
                                } else {
                                    Modifier
                                },
                                onClick = { onNavigateToPicker(sourceId, row.folderKey) }
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
}

@Composable
private fun ReviewRow(
    row: WebDavReviewRow,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val match = row.match
    val summary = when {
        match == null -> stringResource(R.string.library_unmatched)
        match.excluded -> stringResource(R.string.webdav_review_excluded, match.displayName)
        else -> {
            listOfNotNull(
                match.displayName,
                match.season?.let { stringResource(R.string.ratings_season_label, it) },
                match.placementStep.label()
            ).joinToString(" · ")
        }
    }

    SettingsActionRow(
        modifier = modifier,
        title = row.folderName,
        subtitle = "${quantityStringResource(R.plurals.library_files, row.fileCount)} · $summary",
        leadingIcon = when {
            match == null -> Icons.Default.HelpOutline
            match.excluded -> Icons.Default.VisibilityOff
            else -> Icons.Default.Movie
        },
        titleTrailingIcon = if (match == null) Icons.Default.ErrorOutline else null,
        titleTrailingIconTint = NuvioTheme.colors.Error,
        onClick = onClick
    )
}

/** Unresolved, then weakly resolved, then settled. */
private fun bucket(rows: List<WebDavReviewRow>): List<Pair<Int, List<WebDavReviewRow>>> {
    val unmatched = rows.filter {
        it.match == null || it.match.placementStep == PlacementStep.UNRESOLVED
    }
    val uncertain = rows.filter { row ->
        val match = row.match ?: return@filter false
        match.placementStep != PlacementStep.UNRESOLVED &&
            !match.userSet &&
            (match.confidence < LOW_CONFIDENCE ||
                match.placementStep == PlacementStep.FLATTENED_ABSOLUTE)
    }
    val settledKeys = (unmatched + uncertain).mapTo(HashSet()) { it.folderKey }
    val settled = rows.filterNot { it.folderKey in settledKeys }

    return listOf(
        R.string.library_unmatched to unmatched,
        R.string.webdav_review_worth_checking to uncertain,
        R.string.webdav_review_matched to settled
    )
}
