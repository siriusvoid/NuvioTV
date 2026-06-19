@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun ManualMatchPickerScreen(
    sourceId: String,
    itemKey: String,
    onBackPress: () -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
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

    if (item == null) {
        SettingsStandaloneScaffold(title = "Pick match", subtitle = "") {
            Text("Item no longer in unmatched list.", color = NuvioColors.TextSecondary)
        }
        return
    }

    SettingsStandaloneScaffold(
        title = "Pick match",
        subtitle = item.fileName
    ) {
        SettingsDetailHeader(
            title = "Top TMDB results",
            subtitle = "Selecting one stores a permanent override for this file."
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TypeChip("Series", selectedType == ContentType.SERIES) { selectedType = ContentType.SERIES }
            TypeChip("Movie", selectedType == ContentType.MOVIE) { selectedType = ContentType.MOVIE }
            if (selectedType == ContentType.SERIES) {
                TypeChip("Whole folder", applyToFolder) { applyToFolder = !applyToFolder }
            }
        }

        SettingsGroupCard(modifier = Modifier.fillMaxSize()) {
            if (candidates.isEmpty()) {
                Text(
                    text = "No candidates returned. Try the other type above, or rename the file with a clear title.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(8.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(candidates, key = { it.id }) { candidate ->
                        val title = candidate.title ?: candidate.name ?: "Untitled"
                        val year = (candidate.releaseDate ?: candidate.firstAirDate)?.take(4)
                        SettingsActionRow(
                            title = title,
                            subtitle = buildString {
                                year?.let { append(it) }
                                candidate.overview?.takeIf { it.isNotBlank() }?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append(it.take(120))
                                }
                            }.takeIf { it.isNotBlank() },
                            onClick = {
                                if (selectedType == ContentType.SERIES && applyToFolder) {
                                    viewModel.matchFolder(item, candidate, selectedType)
                                } else {
                                    viewModel.pickCandidate(item, candidate, selectedType)
                                }
                                onBackPress()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioColors.FocusRing else NuvioColors.Background,
            focusedContainerColor = if (selected) NuvioColors.FocusRing else NuvioColors.BackgroundElevated
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(999.dp))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = if (selected) Color.Black else NuvioColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
