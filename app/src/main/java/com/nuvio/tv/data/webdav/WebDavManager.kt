package com.nuvio.tv.data.webdav

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.WebDavPreferences
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.webdav.ParsedRelease
import com.nuvio.tv.domain.model.webdav.PlacementStep
import com.nuvio.tv.domain.model.webdav.ScanPhase
import com.nuvio.tv.domain.model.webdav.WebDavConnectionResult
import com.nuvio.tv.domain.model.webdav.WebDavFolder
import com.nuvio.tv.domain.model.webdav.WebDavMatch
import com.nuvio.tv.domain.model.webdav.WebDavProvider
import com.nuvio.tv.domain.model.webdav.WebDavReviewRow
import com.nuvio.tv.domain.model.webdav.WebDavScanProgress
import com.nuvio.tv.domain.model.webdav.WebDavSource
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the WebDAV sources, their scans and the resulting matches.
 *
 * Everything here is device-local: nothing about a WebDAV source syncs to an
 * account. The scanned index is exposed to the rest of the app through
 * [WebDavGatewayImpl], which dresses it as a synthetic addon.
 *
 * Scans run on an app-scoped supervisor scope so starting one from a settings
 * screen survives that screen leaving the foreground.
 */
@Singleton
internal class WebDavManager @Inject constructor(
    private val preferences: WebDavPreferences,
    private val index: WebDavIndex,
    private val http: WebDavHttp,
    private val animeSearch: AnimeSearchClient,
    private val armMapping: ArmMappingClient,
    // Lazy on both: the addon and meta repositories reach the WebDAV gateway, and
    // the gateway reaches back here.
    private val addonRepository: Lazy<AddonRepository>,
    private val metaRepository: Lazy<MetaRepository>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanJobs = ConcurrentHashMap<String, Job>()

    private val _sources = MutableStateFlow<List<WebDavSource>>(emptyList())
    val sources: StateFlow<List<WebDavSource>> = _sources.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, WebDavScanProgress>>(emptyMap())
    val progress: StateFlow<Map<String, WebDavScanProgress>> = _progress.asStateFlow()

    private val _counts = MutableStateFlow<Map<String, WebDavSourceCounts>>(emptyMap())
    val counts: StateFlow<Map<String, WebDavSourceCounts>> = _counts.asStateFlow()

    /**
     * Bumped whenever the catalogue's contents change, and carried in the synthetic
     * addon's version. Without it the manifest is identical after a scan and the
     * home rows never learn to refetch.
     */
    private val _catalogRevision = MutableStateFlow(0)
    val catalogRevision: StateFlow<Int> = _catalogRevision.asStateFlow()

    private var launchScanStarted = false

    init {
        scope.launch {
            preferences.sources.collect { stored ->
                _sources.value = stored
                index.publishSourceIds(stored.map { it.id })
                refreshCounts()
            }
        }
    }

    // ---------------------------------------------------------------- sources

    suspend fun testConnection(
        baseUrl: String,
        username: String,
        password: String,
        rootPath: String
    ): WebDavConnectionResult {
        val normalizedBase = WebDavUrl.normalizeBaseUrl(baseUrl)
        if (normalizedBase.isBlank()) {
            return WebDavConnectionResult.Failure("Enter the server address first.")
        }
        return WebDavClient(http, normalizedBase, username.trim(), password.trim())
            .testConnection(WebDavUrl.normalizeRootPath(rootPath))
    }

    suspend fun addSource(
        provider: WebDavProvider,
        displayName: String,
        baseUrl: String,
        username: String,
        password: String,
        rootPath: String,
        windowSize: Int = WebDavSource.DEFAULT_WINDOW_SIZE
    ): Result<WebDavSource> {
        val normalizedBase = WebDavUrl.normalizeBaseUrl(baseUrl)
        val effectiveUsername = (provider.fixedUsername ?: username).trim()
        val trimmedPassword = password.trim()
        val normalizedRoot = WebDavUrl.normalizeRootPath(rootPath)

        val test = WebDavClient(http, normalizedBase, effectiveUsername, trimmedPassword)
            .testConnection(normalizedRoot)
        if (test is WebDavConnectionResult.Failure) {
            return Result.failure(IllegalStateException(test.message))
        }

        val source = WebDavSource(
            id = "webdav-${System.currentTimeMillis()}",
            providerId = provider.id,
            displayName = displayName.ifBlank { provider.displayName },
            baseUrl = normalizedBase,
            username = effectiveUsername,
            rootPath = normalizedRoot,
            windowSize = windowSize
        )

        preferences.savePassword(source.id, trimmedPassword)
        preferences.upsert(source)
        publishCatalogChange()
        scan(source.id)
        return Result.success(source)
    }

    suspend fun removeSource(sourceId: String) {
        scanJobs.remove(sourceId)?.cancel()
        preferences.remove(sourceId)
        index.deleteSource(sourceId)
        refreshCounts()
        publishCatalogChange()
    }

    suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        preferences.setEnabled(sourceId, enabled)
        publishCatalogChange()
    }

    suspend fun setWindowSize(sourceId: String, windowSize: Int) =
        preferences.setWindowSize(sourceId, windowSize)

    suspend fun playbackHeaders(sourceId: String): Map<String, String> {
        val source = source(sourceId) ?: return emptyMap()
        val password = preferences.password(sourceId)
        return WebDavClient(http, source.baseUrl, source.username, password).playbackHeaders()
    }

    /**
     * The stored source, read through the DataStore rather than the cached state.
     * A source added moments ago is on disk before the collector has published it,
     * and the scan that follows the add would otherwise find nothing to scan.
     */
    private suspend fun source(sourceId: String): WebDavSource? =
        _sources.value.firstOrNull { it.id == sourceId }
            ?: preferences.currentSources().firstOrNull { it.id == sourceId }

    // ------------------------------------------------------------------ scans

    fun scan(sourceId: String, windowStart: Int = 0) {
        if (scanJobs[sourceId]?.isActive == true) return

        scanJobs[sourceId] = scope.launch {
            val source = source(sourceId) ?: return@launch
            publishProgress(sourceId) { it.copy(phase = ScanPhase.LISTING, errorMessage = null) }

            val password = preferences.password(sourceId)
            val client = WebDavClient(http, source.baseUrl, source.username, password)
            val scanner = WebDavScanner(source, client)
            val known = index.folders(sourceId).associateBy { it.path }

            val result = scanner.scanWindow(
                known = known,
                windowStart = windowStart,
                onProgress = { done, planned, files ->
                    publishProgress(sourceId) {
                        it.copy(
                            phase = ScanPhase.FOLDERS,
                            foldersDone = done,
                            foldersPlanned = planned,
                            filesFound = files
                        )
                    }
                }
            )

            result.fold(
                onSuccess = { scanned ->
                    val deleted = confirmDeletions(source, scanner, scanned)
                    index.mergeFolders(sourceId, scanned.folders, deleted)
                    publishProgress(sourceId) {
                        it.copy(
                            phase = ScanPhase.MATCHING,
                            knownFolderCount = scanned.totalFolderCount
                        )
                    }
                    matchFolders(source, scanned.folders)
                    preferences.markScanned(
                        sourceId = sourceId,
                        listingCount = scanned.presentPaths.size,
                        scannedAt = System.currentTimeMillis()
                    )
                    publishProgress(sourceId) { it.copy(phase = ScanPhase.DONE) }
                    refreshCounts()
                    publishCatalogChange()
                },
                onFailure = { error ->
                    Log.w(TAG, "Scan failed for $sourceId", error)
                    publishProgress(sourceId) {
                        it.copy(
                            phase = ScanPhase.FAILED,
                            errorMessage = error.message ?: "The scan could not finish."
                        )
                    }
                }
            )
        }
    }

    /**
     * Which indexed folders the server confirms are gone.
     *
     * Both guards below target the same failure — a listing that came back short —
     * and skip the prune outright rather than delete on the strength of bad data.
     */
    private suspend fun confirmDeletions(
        source: WebDavSource,
        scanner: WebDavScanner,
        scanned: WebDavScanResult
    ): Set<String> {
        if (scanned.presentPaths.isEmpty()) return emptySet()

        val previousCount = source.lastListingCount
        if (previousCount != null && scanned.presentPaths.size < previousCount * LISTING_SHRINK_FLOOR) {
            return emptySet()
        }

        val missing = index.folders(source.id)
            .map { it.path }
            .filterNot { it in scanned.presentPaths }
        if (missing.isEmpty() || missing.size > MAX_DELETION_CHECKS) return emptySet()

        return scanner.confirmDeleted(missing)
    }

    /**
     * Rescans every enabled source, once per launch. Safe to call whenever the app
     * comes to the foreground: only the first call in the process does anything.
     * Everything after that is the Scan now button.
     */
    fun scanOnLaunch() {
        if (launchScanStarted) return
        launchScanStarted = true
        scope.launch {
            preferences.currentSources()
                .filter { it.enabled }
                .forEach { source -> scan(source.id) }
        }
    }

    fun rescanAll() {
        _sources.value.filter { it.enabled }.forEach { scan(it.id) }
    }

    /**
     * Clears everything indexed for a source and scans it again. Needed when the scan
     * rules themselves change: unchanged folders are otherwise reused from the index
     * and never re-listed.
     */
    fun rebuild(sourceId: String) {
        scanJobs.remove(sourceId)?.cancel()
        scope.launch {
            index.deleteSource(sourceId)
            refreshCounts()
            scan(sourceId)
        }
    }

    // ---------------------------------------------------------------- matching

    private suspend fun matchFolders(source: WebDavSource, folders: List<WebDavFolder>) {
        val existing = index.matches()
        val resolved = ArrayList<WebDavMatch>()

        folders.forEachIndexed { position, folder ->
            val current = existing[folder.key]
            val alreadySettled = current != null &&
                (current.userSet || current.excluded ||
                    current.placementStep != PlacementStep.UNRESOLVED)
            if (!alreadySettled) {
                val match = runCatching { resolveFolder(source, folder) }
                    .getOrElse { error ->
                        Log.w(TAG, "Could not resolve ${folder.name}", error)
                        null
                    }
                if (match != null) resolved.add(match)
            }

            publishProgress(source.id) {
                it.copy(phase = ScanPhase.MATCHING, matchesResolved = position + 1)
            }
        }

        index.putMatches(resolved)
    }

    private suspend fun resolveFolder(source: WebDavSource, folder: WebDavFolder): WebDavMatch? {
        val parsed = AnimeReleaseParser.parseFolder(folder.name)
        if (parsed.title.isBlank()) return null

        val files = folder.files
        val hits = animeSearch.search(parsed.title)
        if (hits.isEmpty()) return null

        val packSize = files.size
        val (hit, confidence) = hits
            .map { candidate -> candidate to scoreHit(candidate, parsed, packSize) }
            .maxBy { it.second }
        if (confidence < MIN_CONFIDENCE) return null

        // Obscure titles have no entry in the mapper. The metadata addon serves anime id
        // spaces directly, so fall back to the search hit's own id rather than dropping
        // the folder.
        val arm = armMapping.lookup(hit.source, hit.id)
        val isMovie = hit.isMovie || arm?.media.equals("MOVIE", ignoreCase = true)
        val contentType = if (isMovie) {
            WebDavMatch.CONTENT_TYPE_MOVIE
        } else {
            WebDavMatch.CONTENT_TYPE_SERIES
        }
        val contentId = pickContentId(arm, contentType, hit) ?: return null

        val meta = fetchMeta(contentType, contentId)

        if (contentType == WebDavMatch.CONTENT_TYPE_MOVIE) {
            return WebDavMatch(
                folderKey = folder.key,
                sourceId = source.id,
                folderPath = folder.path,
                contentId = contentId,
                contentType = contentType,
                title = hit.title,
                poster = hit.poster,
                metaName = meta?.name,
                metaPoster = meta?.poster,
                step = PlacementStep.MAPPER_SEASON.name,
                confidence = confidence
            )
        }

        val episodes = meta.toEpisodeSlots()
        val firstEpisode = files
            .mapNotNull { AnimeReleaseParser.parseFile(it.fileName).episode }
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

        return WebDavMatch(
            folderKey = folder.key,
            sourceId = source.id,
            folderPath = folder.path,
            contentId = contentId,
            contentType = contentType,
            title = hit.title,
            poster = hit.poster,
            metaName = meta?.name,
            metaPoster = meta?.poster,
            season = placement?.season ?: arm?.season,
            // The offset falls out of placement: for a pack numbered inside its own
            // cour it is zero, and for an absolute-numbered long-runner it shifts the
            // whole folder onto the right season.
            episodeOffset = if (placement != null && firstEpisode != null) {
                placement.episode - firstEpisode
            } else {
                0
            },
            step = (placement?.step ?: PlacementStep.UNRESOLVED).name,
            confidence = confidence
        )
    }

    /**
     * The installed metadata addon's view of this item. Used for the episode list and
     * for the catalogue's name and artwork, so rows read the same as the details page.
     */
    private suspend fun fetchMeta(contentType: String, contentId: String): Meta? =
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

    // ----------------------------------------------------------------- review

    /** Newest torrent first, matching the catalogue order. */
    suspend fun reviewRows(sourceId: String): List<WebDavReviewRow> {
        val matches = index.matches()
        return index.folders(sourceId)
            .sortedByDescending { it.modifiedAt ?: Long.MIN_VALUE }
            .map { folder ->
                WebDavReviewRow(
                    folderKey = folder.key,
                    sourceId = sourceId,
                    folderName = folder.name,
                    fileCount = folder.files.size,
                    match = matches[folder.key]
                )
            }
    }

    suspend fun searchForOverride(query: String): List<AnimeSearchHit> = animeSearch.search(query)

    /** Applies a manual correction. Rescans never overwrite it. */
    suspend fun applyOverride(
        folderKey: String,
        hit: AnimeSearchHit,
        season: Int?,
        treatAsMovie: Boolean
    ): Result<WebDavMatch> {
        val sourceId = folderKey.substringBefore('|')
        val folderPath = folderKey.substringAfter('|')
        val arm = armMapping.lookup(hit.source, hit.id)
        val contentType = if (treatAsMovie || hit.isMovie) {
            WebDavMatch.CONTENT_TYPE_MOVIE
        } else {
            WebDavMatch.CONTENT_TYPE_SERIES
        }
        val contentId = pickContentId(arm, contentType, hit)
            ?: return Result.failure(IllegalStateException("No id mapping exists for ${hit.title}."))

        val meta = fetchMeta(contentType, contentId)
        val match = WebDavMatch(
            folderKey = folderKey,
            sourceId = sourceId,
            folderPath = folderPath,
            contentId = contentId,
            contentType = contentType,
            title = hit.title,
            poster = hit.poster,
            metaName = meta?.name,
            metaPoster = meta?.poster,
            season = season ?: arm?.season,
            step = PlacementStep.MANUAL.name,
            confidence = 1f,
            userSet = true
        )
        index.putMatch(match)
        refreshCounts()
        publishCatalogChange()
        return Result.success(match)
    }

    suspend fun setExcluded(folderKey: String, excluded: Boolean) {
        val current = index.match(folderKey) ?: return
        index.putMatch(current.copy(excluded = excluded, userSet = true))
        refreshCounts()
        publishCatalogChange()
    }

    suspend fun rematch(folderKey: String) {
        val sourceId = folderKey.substringBefore('|')
        val source = source(sourceId) ?: return
        val folder = index.folders(sourceId).firstOrNull { it.key == folderKey } ?: return
        index.removeMatch(folderKey)
        runCatching { resolveFolder(source, folder) }.getOrNull()?.let { index.putMatch(it) }
        refreshCounts()
        publishCatalogChange()
    }

    // ------------------------------------------------------------------ state

    private fun publishCatalogChange() {
        _catalogRevision.update { it + 1 }
    }

    private fun publishProgress(
        sourceId: String,
        transform: (WebDavScanProgress) -> WebDavScanProgress
    ) {
        _progress.update { current ->
            val existing = current[sourceId] ?: WebDavScanProgress(sourceId = sourceId)
            current + (sourceId to transform(existing))
        }
    }

    private suspend fun refreshCounts() {
        val matches = index.matches()
        val counts = _sources.value.associate { source ->
            val folders = index.folders(source.id)
            source.id to WebDavSourceCounts(
                folders = folders.size,
                files = folders.sumOf { it.files.size },
                matched = folders.count { folder -> matches[folder.key]?.excluded == false }
            )
        }
        _counts.value = counts
    }

    private companion object {
        const val TAG = "WebDavManager"
        const val MIN_CONFIDENCE = 0.55f
        const val META_TIMEOUT_MS = 8_000L

        /** A listing shorter than this fraction of the last one is treated as truncated. */
        const val LISTING_SHRINK_FLOOR = 0.8

        /** Upper bound on the folders one scan may question, so a bad listing cannot storm. */
        const val MAX_DELETION_CHECKS = 20

        val ORDINAL_SEASON_IN_TITLE =
            Regex("(\\d{1,2})(?:st|nd|rd|th)\\s+Season", RegexOption.IGNORE_CASE)
        val WORD_SEASON_IN_TITLE = Regex("\\bSeason\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
    }
}

/** What one source currently holds, for the settings rows. */
internal data class WebDavSourceCounts(
    val folders: Int = 0,
    val files: Int = 0,
    val matched: Int = 0
)
