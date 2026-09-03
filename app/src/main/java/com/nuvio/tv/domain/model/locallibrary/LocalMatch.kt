package com.nuvio.tv.domain.model.locallibrary

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.ContentType

/** A scanned item's TMDB match; a user-set one is never overwritten by auto-matching. */
@Immutable
data class LocalMatch(
    val itemKey: String,
    val tmdbId: Int,
    val contentType: ContentType,
    /** For series items: the season/episode this file represents. */
    val season: Int? = null,
    val episode: Int? = null,
    val userSet: Boolean = false,
    /** Resolved at match time, so local items line up with trackers' IMDB keys without a lookup. */
    val imdbId: String? = null,
    /** Confidence in [0.0, 1.0]; meaningless when [userSet] is true. */
    val score: Float = 0f
)
