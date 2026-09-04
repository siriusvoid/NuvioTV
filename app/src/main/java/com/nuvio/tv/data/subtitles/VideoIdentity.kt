package com.nuvio.tv.data.subtitles

/** The show, season and episode a playback id spells out. */
internal data class VideoIdentity(
    val metaId: String?,
    val season: Int?,
    val episode: Int?
) {
    companion object {
        /** `tt0972656:1:5` and `kitsu:12345:5` both appear as playback ids. */
        fun parse(videoId: String): VideoIdentity {
            val segments = videoId.split(':')
            if (segments.size >= 3) {
                val season = segments[segments.lastIndex - 1].toIntOrNull()
                val episode = segments.last().toIntOrNull()
                if (season != null && episode != null) {
                    return VideoIdentity(
                        metaId = segments.dropLast(2).joinToString(":"),
                        season = season,
                        episode = episode
                    )
                }
            }
            if (segments.size >= 2) {
                val episode = segments.last().toIntOrNull()
                if (episode != null) {
                    return VideoIdentity(
                        metaId = segments.dropLast(1).joinToString(":"),
                        season = null,
                        episode = episode
                    )
                }
            }
            return VideoIdentity(metaId = videoId, season = null, episode = null)
        }
    }
}
