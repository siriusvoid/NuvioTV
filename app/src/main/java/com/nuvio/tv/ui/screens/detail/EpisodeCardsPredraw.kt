package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.nuvio.tv.domain.model.EpisodeOptionsOverlayStyle
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress

/** The card the row lands on, the one partly showing before it, and the two after. */
private const val PREDRAW_CARD_COUNT = 4

/**
 * Draws the cards the first jump reveals into a layer that is never shown. A card's first draw
 * makes the GPU prepare its glyphs, shaders and surfaces, which otherwise lands in the jump.
 */
@Composable
internal fun EpisodeCardsPredraw(
    episodes: List<Video>,
    landingEpisodeId: String?,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    episodeRatings: Map<Pair<Int, Int>, Double>,
    watchedEpisodes: Set<Pair<Int, Int>>,
    blurUnwatchedEpisodes: Boolean,
    episodeOptionsOverlayStyle: EpisodeOptionsOverlayStyle,
    posterCardCornerRadiusDp: Int,
    modifier: Modifier = Modifier,
) {
    val cardMetrics = rememberEpisodeCardMetrics(posterCardCornerRadiusDp)
    val cards = remember(episodes, landingEpisodeId) {
        val deduped = episodes.distinctBy { it.id }
        val start = (deduped.indexOfFirst { it.id == landingEpisodeId } - 1).coerceAtLeast(0)
        deduped.drop(start).take(PREDRAW_CARD_COUNT)
    }
    val unusedFocusRequester = remember { FocusRequester() }
    Column(
        modifier = modifier
            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            // Alpha 0 is never composited, but an offscreen layer is still rendered. Two by two keeps
            // it well inside the GPU's texture size limit.
            .graphicsLayer {
                alpha = 0f
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(cardMetrics.itemSpacing),
    ) {
        cards.chunked(2).forEach { rowCards ->
            Row(horizontalArrangement = Arrangement.spacedBy(cardMetrics.itemSpacing)) {
                rowCards.forEach { episode ->
                    key(episode.id) {
                        val seasonEpisode = episode.season?.let { season -> episode.episode?.let { season to it } }
                        EpisodeCard(
                            episode = episode,
                            watchProgress = seasonEpisode?.let { episodeProgressMap[it] },
                            imdbRating = seasonEpisode?.let { episodeRatings[it] },
                            isMarkedWatched = seasonEpisode?.let { it in watchedEpisodes } ?: false,
                            blurUnwatched = blurUnwatchedEpisodes,
                            overlayStyle = episodeOptionsOverlayStyle,
                            cardMetrics = cardMetrics,
                            onClick = {},
                            onLongPress = {},
                            upFocusRequester = unusedFocusRequester,
                            focusRequester = remember { FocusRequester() },
                            isFocusEnabled = false,
                        )
                    }
                }
            }
        }
    }
}
