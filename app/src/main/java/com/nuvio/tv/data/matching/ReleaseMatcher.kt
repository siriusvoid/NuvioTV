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
    /**
     * Added to a parsed episode number to reach the season's own numbering. Falls
     * out of placement: zero for a pack numbered inside its own cour, non-zero for
     * an absolute-numbered long-runner.
     */
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

/**
 * Resolves a parsed release onto a metadata addon's id space.
 *
 * Shared by the WebDAV library and the imported subtitle library: both hold a
 * folder of numbered files that has to become "season N of this show" before it
 * is any use. The ladder is the same either way — title search, ARM mapping for
 * the season a cour-titled entry really belongs to, then episode placement — so
 * it lives here rather than in either caller.
 *
 * The two differ only in where the release title comes from. WebDAV takes it from
 * the folder name, which is what a debrid torrent is named after. Subtitle packs
 * take it from the file names, because their folders are named after the fansub
 * group instead.
 */
@Singleton
internal class ReleaseMatcher @Inject constructor(
    private val animeSearch: AnimeSearchClient,
    private val armMapping: ArmMappingClient,
    // Lazy on both: the addon and meta repositories reach the WebDAV gateway, and
    // the gateway reaches back here.
    private val addonRepository: Lazy<AddonRepository>,
    private val metaRepository: Lazy<MetaRepository>
) {

    /**
     * [parsed] carries the title and any season the name stated; [fileNames] are the
     * files the release holds, used for the pack size and its first episode number.
     * Null means nothing scored above [MIN_CONFIDENCE] — the caller sends it to review
     * rather than guessing, because a wrongly placed episode quietly corrupts watch
     * progress and everything syncing from it.
     */
    suspend fun match(parsed: ParsedRelease, fileNames: List<String>): ReleaseMatch? {
        if (parsed.title.isBlank()) return null

        val hits = animeSearch.search(parsed.title)
        if (hits.isEmpty()) return null

        val packSize = fileNames.size
        val (hit, confidence) = hits
            .map { candidate -> candidate to scoreHit(candidate, parsed, packSize) }
            .maxBy { it.second }
        if (confidence < MIN_CONFIDENCE) return null

        // Obscure titles have no entry in the mapper. The metadata addon serves anime id
        // spaces directly, so fall back to the search hit's own id rather than dropping
        // the release.
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

    /**
     * The id space and metadata for a hit the user picked by hand, skipping the
     * scoring and placement the automatic path runs.
     */
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

        // A cour is its own entry in the anime databases, so a season stated in the
        // release name should pull the matching entry up and push the others down.
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

    /**
     * Emits the id space the installed metadata addon actually serves, so mapped
     * items are the same objects as the ones already in the user's catalogue.
     */
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

    /**
     * The installed metadata addon's view of this item. Used for the episode list and
     * for the catalogue's name and artwork, so rows read the same as the details page.
     */
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
