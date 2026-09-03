package com.nuvio.tv.data.locallibrary.match

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.MatchOverrideStore
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.LocalMatch
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Resolves a scanned item to TMDB: user override, cached match, id hint, then scored search. */
@Singleton
class MediaMatcher @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val overrideStore: MatchOverrideStore
) {

    /** Resolved at match time and stored, so reads never look it up. */
    internal suspend fun resolveImdbId(tmdbId: Int, contentType: ContentType): String? {
        val mediaType = if (contentType == ContentType.MOVIE) "movie" else "tv"
        return runCatching { tmdbService.tmdbToImdb(tmdbId, mediaType) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** Null below [AUTO_MATCH_THRESHOLD]; stores the result without overwriting user overrides. */
    suspend fun match(item: ScannedItem, language: String = "en-US"): LocalMatch? =
        withContext(Dispatchers.IO) {
            val cached = overrideStore.get(item.itemKey)
            // A user override always wins; auto matches are re-checked against the current type below.
            if (cached?.userSet == true) return@withContext cached

            item.tmdbHintId?.let { hintId ->
                val hintType = item.typeHint.takeIf { it != ContentType.UNKNOWN } ?: ContentType.MOVIE
                val match = LocalMatch(
                    itemKey = item.itemKey,
                    tmdbId = hintId,
                    contentType = hintType,
                    season = item.parsedSeason,
                    episode = item.parsedEpisode,
                    userSet = false,
                    imdbId = resolveImdbId(hintId, hintType),
                    score = 1f
                )
                overrideStore.put(match)
                return@withContext match
            }

            val parsed = FilenameParser.parse(item.fileName)
            val parsedTitle = item.parsedTitle?.takeIf { it.isNotBlank() } ?: parsed.title
            val type = when {
                // A parsed S/E is authoritative, even over a stale MOVIE type hint.
                parsed.season != null && parsed.episode != null -> ContentType.SERIES
                item.typeHint == ContentType.SERIES -> ContentType.SERIES
                item.typeHint == ContentType.MOVIE -> ContentType.MOVIE
                else -> parsed.contentType
            }
            val year = item.parsedYear ?: parsed.year

            // Reuse an auto match only while its type still agrees.
            if (cached != null && !cached.userSet && cached.contentType == type) {
                return@withContext cached
            }

            val candidates = searchCandidates(parsedTitle, year, type, language)
            val best = scoreAndPickBest(candidates, parsedTitle, year)
            if (best == null || best.second < AUTO_MATCH_THRESHOLD) {
                Log.i(TAG, "No match for '${item.fileName}' parsedTitle='$parsedTitle' year=$year type=$type bestScore=${best?.second} bestCandidate=${best?.first?.title ?: best?.first?.name}")
                return@withContext null
            }
            Log.i(TAG, "Matched '${item.fileName}' -> tmdbId=${best.first.id} (${best.first.title ?: best.first.name}) score=${best.second}")
            val match = LocalMatch(
                itemKey = item.itemKey,
                tmdbId = best.first.id,
                contentType = type,
                season = item.parsedSeason ?: parsed.season,
                episode = item.parsedEpisode ?: parsed.episode,
                userSet = false,
                imdbId = resolveImdbId(best.first.id, type),
                score = best.second
            )
            overrideStore.put(match)
            match
        }

    /** Returns up to [limit] TMDB candidates for the manual-match picker UI. */
    suspend fun candidates(
        item: ScannedItem,
        contentType: ContentType? = null,
        language: String = "en-US",
        limit: Int = 5
    ): List<TmdbDiscoverResult> = withContext(Dispatchers.IO) {
        val parsed = FilenameParser.parse(item.fileName)
        val title = item.parsedTitle?.takeIf { it.isNotBlank() } ?: parsed.title
        val year = item.parsedYear ?: parsed.year
        val type = contentType ?: parsed.contentType
        searchCandidates(title, year, type, language).take(limit)
    }

    /** Persists a user-confirmed match. Always sets [LocalMatch.userSet] = true. */
    suspend fun setOverride(
        item: ScannedItem,
        tmdbId: Int,
        contentType: ContentType,
        season: Int? = null,
        episode: Int? = null
    ) {
        overrideStore.put(
            LocalMatch(
                itemKey = item.itemKey,
                tmdbId = tmdbId,
                contentType = contentType,
                season = season ?: item.parsedSeason,
                episode = episode ?: item.parsedEpisode,
                userSet = true,
                imdbId = resolveImdbId(tmdbId, contentType),
                score = 1f
            )
        )
    }

    suspend fun clearOverride(itemKey: String) = overrideStore.remove(itemKey)

    private suspend fun searchCandidates(
        title: String,
        year: Int?,
        type: ContentType,
        language: String
    ): List<TmdbDiscoverResult> {
        if (title.isBlank()) return emptyList()
        return try {
            val resp = when (type) {
                ContentType.SERIES, ContentType.TV -> tmdbApi.searchTv(
                    apiKey = TMDB_API_KEY,
                    query = title,
                    firstAirDateYear = year,
                    language = language
                )
                else -> tmdbApi.searchMovie(
                    apiKey = TMDB_API_KEY,
                    query = title,
                    year = year,
                    language = language
                )
            }
            resp.body()?.results.orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "TMDB search failed for '$title'", t)
            emptyList()
        }
    }

    private fun scoreAndPickBest(
        candidates: List<TmdbDiscoverResult>,
        query: String,
        queryYear: Int?
    ): Pair<TmdbDiscoverResult, Float>? {
        if (candidates.isEmpty()) return null
        return candidates
            .mapIndexed { index, c -> c to score(c, query, queryYear, index, candidates.size) }
            .maxByOrNull { it.second }
    }

    private fun score(
        result: TmdbDiscoverResult,
        query: String,
        queryYear: Int?,
        rank: Int,
        total: Int
    ): Float {
        // Also compare the main title before ": Subtitle"; anime are listed as "Romaji: English".
        val candidateTitles = listOfNotNull(
            result.title, result.name, result.originalTitle, result.originalName
        ).flatMap { titleVariants(it) }.map { it.lowercase() }.distinct()
        val queryTitles = titleVariants(query).map { it.lowercase() }.distinct()
        val titleSim = candidateTitles.maxOfOrNull { c ->
            queryTitles.maxOfOrNull { qq -> titleSimilarity(qq, c) } ?: 0f
        } ?: 0f

        val resultYear = (result.releaseDate ?: result.firstAirDate)
            ?.take(4)
            ?.toIntOrNull()
        val yearScore = when {
            queryYear == null || resultYear == null -> 0.5f
            queryYear == resultYear -> 1f
            abs(queryYear - resultYear) == 1 -> 0.6f
            abs(queryYear - resultYear) <= 2 -> 0.3f
            else -> 0f
        }

        val popularity = result.popularity ?: 0.0
        val popularityScore = (ln(1.0 + popularity) / ln(101.0)).coerceIn(0.0, 1.0).toFloat()

        // Reward TMDB's top hits: the right anime usually ranks first even when titles differ.
        val rankScore = if (total <= 1) 0.6f else (1f - rank.toFloat() / total.toFloat())

        return titleSim * 0.55f + yearScore * 0.2f + popularityScore * 0.1f + rankScore * 0.15f
    }

    /** A title plus its main part before a "Main: Subtitle" / "Main - Subtitle" split. */
    private fun titleVariants(raw: String): List<String> {
        val base = raw.trim()
        if (base.isBlank()) return emptyList()
        val variants = linkedSetOf(base)
        base.substringBefore(':').trim()
            .takeIf { it.isNotBlank() && it != base }?.let { variants += it }
        Regex("""\s[-–]\s""").split(base).firstOrNull()?.trim()
            ?.takeIf { it.isNotBlank() && it != base }?.let { variants += it }
        return variants.toList()
    }

    /** Best of Levenshtein, token Jaccard and a subset bonus, so "F1" still matches "F1 The Movie". */
    private fun titleSimilarity(query: String, candidate: String): Float {
        if (query.isBlank() || candidate.isBlank()) return 0f
        if (query == candidate) return 1f
        val qTokens = query.split(' ').filter { it.isNotBlank() }.toSet()
        val cTokens = candidate.split(' ').filter { it.isNotBlank() }.toSet()
        if (qTokens.isEmpty() || cTokens.isEmpty()) {
            return normalizedLevenshtein(query, candidate)
        }
        val intersection = qTokens.intersect(cTokens).size.toFloat()
        val union = qTokens.union(cTokens).size.toFloat()
        val jaccard = if (union > 0f) intersection / union else 0f
        val subsetBonus = when {
            qTokens.containsAll(cTokens) && cTokens.containsAll(qTokens) -> 1f
            qTokens.containsAll(cTokens) || cTokens.containsAll(qTokens) -> {
                val ratio = min(qTokens.size, cTokens.size).toFloat() /
                    max(qTokens.size, cTokens.size).toFloat()
                max(ratio, 0.7f)
            }
            else -> 0f
        }
        return max(max(normalizedLevenshtein(query, candidate), jaccard), subsetBonus)
    }

    private fun normalizedLevenshtein(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = max(a.length, b.length)
        val distance = levenshtein(a, b)
        return 1f - distance.toFloat() / maxLen.toFloat()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    companion object {
        private const val TAG = "MediaMatcher"
        private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
        private const val AUTO_MATCH_THRESHOLD = 0.6f
    }
}
