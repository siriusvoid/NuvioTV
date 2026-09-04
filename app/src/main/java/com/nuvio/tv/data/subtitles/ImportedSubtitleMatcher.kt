package com.nuvio.tv.data.subtitles

import com.nuvio.tv.data.webdav.AnimeReleaseParser
import com.nuvio.tv.data.webdav.EpisodePlacement
import com.nuvio.tv.data.webdav.EpisodeSlot
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitleFile

/**
 * Decides which episode each imported file belongs to.
 *
 * The show is already known — the import starts from its details page — so this
 * is only the numbering problem, and it runs the same ladder the WebDAV library
 * uses: a season the name states wins, then a season that fits the pack size,
 * then the number read as absolute across the whole run. Specials keep season 0.
 */
internal object ImportedSubtitleMatcher {

    /** Reads the episode number out of a file name, before any placement. */
    fun parse(fileName: String): ImportedSubtitleFile {
        val parsed = AnimeReleaseParser.parseFile(fileName)
        return ImportedSubtitleFile(
            fileName = fileName,
            relativePath = "",
            parsedEpisode = parsed.episode,
            parsedSeason = parsed.season
        )
    }

    /** A season stated by the folder the files came from, e.g. "… 2nd Season". */
    fun seasonHint(sourceName: String?): Int? {
        val name = sourceName?.takeIf { it.isNotBlank() } ?: return null
        return AnimeReleaseParser.parseFolder(name).season
    }

    /** The release title the files agree on, used to look the season up. */
    fun releaseTitle(fileNames: List<String>): String? = fileNames
        .map { AnimeReleaseParser.parseFile(it).title }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

    /** Fills in season, episode and the metadata addon's video id for every file. */
    fun place(
        files: List<ImportedSubtitleFile>,
        meta: Meta?,
        seasonHint: Int?,
        isMovie: Boolean,
        metaId: String
    ): List<ImportedSubtitleFile> {
        if (isMovie) {
            return files.map { file -> file.copy(videoId = metaId, season = null, episode = null) }
        }

        val videos = meta?.videos.orEmpty()
        val slots = videos.mapNotNull { video ->
            val season = video.season ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            EpisodeSlot(season = season, episode = episode, releasedEpochSeconds = null)
        }
        val videoIds = videos.mapNotNull { video ->
            val season = video.season ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            (season to episode) to video.id
        }.toMap()

        val numberedSlots = slots.filter { it.season != 0 }

        return files.map { file ->
            val isSpecial = file.parsedSeason == 0
            val placement = EpisodePlacement.place(
                parsedEpisode = file.parsedEpisode,
                parsedSeason = file.parsedSeason,
                mapperSeason = seasonHint,
                packSize = files.size,
                entryStartEpochSeconds = null,
                // Specials are placed among specials and numbered episodes among the
                // numbered ones. Reading "- 01" as absolute over a list that starts with
                // an OVA would drop the first episode of the run onto that OVA.
                episodes = if (isSpecial) slots else numberedSlots
            )
            file.copy(
                season = placement?.season,
                episode = placement?.episode,
                videoId = placement?.let { videoIds[it.season to it.episode] }
            )
        }
    }
}

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
