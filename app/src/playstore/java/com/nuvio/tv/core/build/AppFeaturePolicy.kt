package com.nuvio.tv.core.build

import com.nuvio.tv.BuildConfig

// Shared by the playstore (lean) and nuvio (personal) flavors. Fields that differ
// between the two read from BuildConfig so a single source serves both.
object AppFeaturePolicy {
    val pluginsEnabled: Boolean = false
    // nuvio keeps the real in-app updater (OTA from GitHub Releases); playstore has it off.
    val inAppUpdatesEnabled: Boolean = BuildConfig.FEATURE_IN_APP_UPDATES_ENABLED
    val inAppTrailerPlaybackEnabled: Boolean = false
    val externalTrailerPlaybackEnabled: Boolean = BuildConfig.FEATURE_EXTERNAL_TRAILERS_ENABLED
    val supportNuvioEnabled: Boolean = false
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    val imdbRatingLogoEnabled: Boolean = BuildConfig.FEATURE_IMDB_RATING_LOGO_ENABLED
}
