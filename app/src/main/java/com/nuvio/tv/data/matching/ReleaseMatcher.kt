package com.nuvio.tv.data.matching

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.webdav.AnimeReleaseParser
import com.nuvio.tv.data.webdav.AnimeSearchClient
import com.nuvio.tv.data.webdav.AnimeSearchHit
import com.nuvio.tv.data.webdav.ArmIds
import com.nuvio.tv.data.webdav.ArmMappingClient
import com.nuvio.tv.data.webdav.EpisodePlacement
import com.nuvio.tv.data.webdav.EpisodeSlot
import com.nuvio.tv.data.webdav.parseIsoDateToEpochSeconds
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.webdav.ParsedRelease
import com.nuvio.tv.domain.model.webdav.PlacementStep
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.Lazy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** A release resolved onto something the installed metadata addon serves. */
internal data class ReleaseMatch(
    val contentId: String,
    val contentType: String,
    /** Title of the database entry that won, for showing what was matched. */
    val title: String,
    val poster: String?,
    val meta: Meta?,
    val season: Int?,
    /** Added to a parsed episode to reach the season's numbering: zero within a cour, non-zero if absolute. */
    val episodeOffset: Int,
    val step: PlacementStep,
    val confidence: Float
) {
    companion object {
        const val CONTENT_TYPE_MOVIE = "movie"
        const val CONTENT_TYPE_SERIES = "series"
    }
}

/** A hit the user picked by hand, resolved onto the addon's id space. */
internal data class ResolvedHit(
    val contentId: String,
    val contentType: String,
    val meta: Meta?,
    val armSeason: Int?
)

/** Title search, ARM season mapping, then episode placement; shared by WebDAV and imported subtitles. */
@Singleton
internal class ReleaseMatcher @Inject constructor(
    private val animeSearch: AnimeSearchClient,
    private val armMapping: ArmMappingClient,
    // Lazy: both repositories reach the WebDAV gateway, which reaches back here.
    private val addonRepository: Lazy<AddonRepository>,
    private val metaRepository: Lazy<MetaRepository>
) {

    /** Null when nothing clears [MIN_CONFIDENCE]; the caller sends it to review rather than guess. */
    suspend fun match(parsed: ParsedRelease, fileNames: List<String>): ReleaseMatch? {
        if (parsed.title.isBlank()) return null

        val hits = animeSearch.search(parsed.title)
        if (hits.isEmpty()) return null

        val packSize = fileNames.size
        val (hit, confidence) = hits
            .map { candidate -> candidate to scoreHit(candidate, parsed, packSize) }
            .maxBy { it.second }
        if (confidence < MIN_CONFIDENCE) return null

        // Obscure titles aren't in the mapper; the addon serves anime ids directly, so use the hit's own id.
        val arm = armMapping.lookup(hit.source, hit.id)
        val isMovie = hit.isMovie || arm?.media.equals("MOVIE", ignoreCase = true)
        val contentType = if (isMovie) {
            ReleaseMatch.CONTENT_TYPE_MOVIE
        } else {
            ReleaseMatch.CONTENT_TYPE_SERIES
        }
        val contentId = pickContentId(arm, contentType, hit) ?: return null

        val meta = fetchMeta(contentType, contentId)

        if (contentType == ReleaseMatch.CONTENT_TYPE_MOVIE) {
            return ReleaseMatch(
                contentId = contentId,
                contentType = contentType,
                title = hit.title,
                poster = hit.poster,
                meta = meta,
                season = null,
                episodeOffset = 0,
                step = PlacementStep.MAPPER_SEASON,
                confidence = confidence
            )
        }

        val episodes = meta.toEpisodeSlots()
        val firstEpisode = fileNames
            .mapNotNull { AnimeReleaseParser.parseFile(it).episode }
            .minOrNull()
            ?: parsed.episodeRange?.first
            ?: parsed.episode

        val placement = EpisodePlacement.place(
            parsedEpisode = firstEpisode,
            parsedSeason = parsed.season,
            mapperSeason = arm?.season,
            packSize = packSize,
            entryStartEpochSeconds = hit.startDateEpochSeconds,
            episodes = episodes
        )

        return ReleaseMatch(
            contentId = contentId,
            contentType = contentType,
            title = hit.title,
            poster = hit.poster,
            meta = meta,
            season = placement?.season ?: arm?.season,
            episodeOffset = if (placement != null && firstEpisode != null) {
                placement.episode - firstEpisode
            } else {
                0
            },
            step = placement?.step ?: PlacementStep.UNRESOLVED,
            confidence = confidence
        )
    }

    /** Title search, for the manual-match picker. */
    suspend fun search(query: String): List<AnimeSearchHit> = animeSearch.search(query)

    /** Resolves a hand-picked hit, skipping scoring and placement. */
    suspend fun resolveHit(hit: AnimeSearchHit, treatAsMovie: Boolean): ResolvedHit? {
        val arm = armMapping.lookup(hit.source, hit.id)
        val contentType = if (treatAsMovie || hit.isMovie) {
            ReleaseMatch.CONTENT_TYPE_MOVIE
        } else {
            ReleaseMatch.CONTENT_TYPE_SERIES
        }
        val contentId = pickContentId(arm, contentType, hit) ?: return null
        return ResolvedHit(
            contentId = contentId,
            contentType = contentType,
            meta = fetchMeta(contentType, contentId),
            armSeason = arm?.season
        )
    }

    private fun scoreHit(hit: AnimeSearchHit, parsed: ParsedRelease, packSize: Int): Float {
        val titleScore = hit.allTitles.maxOfOrNull { candidate ->
            AnimeReleaseParser.similarity(parsed.title, candidate)
        } ?: 0f

        var score = titleScore
        if (hit.episodeCount != null && packSize > 1 && hit.episodeCount == packSize) score += 0.12f
        if (parsed.episodeRange != null && hit.episodeCount == parsed.episodeRange.last) score += 0.08f

        // A cour is its own database entry, so a season in the name ranks the matching entry first.
        parsed.season?.let { season ->
            val titleSeason = hit.allTitles.firstNotNullOfOrNull { seasonNumberIn(it) }
            when {
                titleSeason == season -> score += 0.15f
                titleSeason != null -> score -= 0.20f
                season > 1 -> score -= 0.05f
            }
        }
        if (hit.subtype?.lowercase() in setOf("special", "ova", "ona") && !parsed.isSpecial) {
            score -= 0.15f
        }
        return score.coerceIn(0f, 1f)
    }

    /** The season a database title names, e.g. "2nd Season" or "Season 2". */
    private fun seasonNumberIn(title: String): Int? =
        ORDINAL_SEASON_IN_TITLE.find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: WORD_SEASON_IN_TITLE.find(title)?.groupValues?.get(1)?.toIntOrNull()

    /** Uses the id space the installed addon serves, so matches are the catalogue's own items. */
    private suspend fun pickContentId(
        arm: ArmIds?,
        contentType: String,
        hit: AnimeSearchHit
    ): String? {
        val candidates = buildList {
            arm?.imdb?.let { add(it) }
            arm?.themoviedb?.let { add("tmdb:$it") }
            arm?.thetvdb?.let { add("tvdb:$it") }
            arm?.kitsu?.let { add("kitsu:$it") }
            arm?.myanimelist?.let { add("mal:$it") }
            arm?.anilist?.let { add("anilist:$it") }
            arm?.anidb?.let { add("anidb:$it") }
            // Last resort: the id the search itself returned.
            when (hit.source) {
                AnimeSearchHit.SOURCE_KITSU -> add("kitsu:${hit.id}")
                AnimeSearchHit.SOURCE_MAL -> add("mal:${hit.id}")
            }
        }
        if (candidates.isEmpty()) return null

        val servedPrefixes = runCatching {
            addonRepository.get().getInstalledAddons().first()
        }.getOrDefault(emptyList())
            .filter { it.enabled }
            .filter { addon -> addon.servesMetaFor(contentType) }
            .flatMap { addon ->
                addon.resources.filter { it.name == "meta" }.flatMap { it.idPrefixes.orEmpty() } +
                    addon.idPrefixes
            }
            .filter { it.isNotBlank() }
            .distinct()

        return candidates.firstOrNull { candidate ->
            servedPrefixes.any { prefix -> candidate.startsWith(prefix) }
        } ?: candidates.first()
    }

    private fun Addon.servesMetaFor(contentType: String): Boolean =
        resources.any { resource ->
            resource.name == "meta" && resource.types.any { type ->
                type == contentType || type.endsWith(".$contentType") || type == "anime"
            }
        }

    /** The addon's view of the item, so rows read the same as the details page. */
    suspend fun fetchMeta(contentType: String, contentId: String): Meta? =
        withTimeoutOrNull(META_TIMEOUT_MS) {
            val result = runCatching {
                metaRepository.get().getMetaFromAllAddons(contentType, contentId)
                    .first { it !is NetworkResult.Loading }
            }.getOrNull()
            (result as? NetworkResult.Success)?.data
        }

    private fun Meta?.toEpisodeSlots(): List<EpisodeSlot> {
        val meta = this ?: return emptyList()
        return meta.videos.mapNotNull { video ->
            val season = video.season ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            EpisodeSlot(
                season = season,
                episode = episode,
                releasedEpochSeconds = parseIsoDateToEpochSeconds(video.released)
            )
        }
    }

    private companion object {
        const val MIN_CONFIDENCE = 0.55f
        const val META_TIMEOUT_MS = 8_000L

        val ORDINAL_SEASON_IN_TITLE =
            Regex("(\\d{1,2})(?:st|nd|rd|th)\\s+Season", RegexOption.IGNORE_CASE)
        val WORD_SEASON_IN_TITLE = Regex("\\bSeason\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
    }
}
