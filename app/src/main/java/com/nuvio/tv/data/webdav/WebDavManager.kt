package com.nuvio.tv.data.webdav

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.data.matching.ReleaseMatcher
import com.nuvio.tv.data.local.WebDavPreferences
import com.nuvio.tv.domain.model.webdav.PlacementStep
import com.nuvio.tv.domain.model.webdav.ScanPhase
import com.nuvio.tv.domain.model.webdav.WebDavConnectionResult
import com.nuvio.tv.domain.model.webdav.WebDavFolder
import com.nuvio.tv.domain.model.webdav.WebDavMatch
import com.nuvio.tv.domain.model.webdav.WebDavProvider
import com.nuvio.tv.domain.model.webdav.WebDavReviewRow
import com.nuvio.tv.domain.model.webdav.WebDavScanProgress
import com.nuvio.tv.domain.model.webdav.WebDavSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owns WebDAV sources, scans and matches, device-local; scans run on the app scope to outlive the screen. */
@Singleton
internal class WebDavManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: WebDavPreferences,
    private val index: WebDavIndex,
    private val http: WebDavHttp,
    private val releaseMatcher: ReleaseMatcher
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanJobs = ConcurrentHashMap<String, Job>()

    private val _sources = MutableStateFlow<List<WebDavSource>>(emptyList())
    val sources: StateFlow<List<WebDavSource>> = _sources.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, WebDavScanProgress>>(emptyMap())
    val progress: StateFlow<Map<String, WebDavScanProgress>> = _progress.asStateFlow()

    private val _counts = MutableStateFlow<Map<String, WebDavSourceCounts>>(emptyMap())
    val counts: StateFlow<Map<String, WebDavSourceCounts>> = _counts.asStateFlow()

    /** Carried in the synthetic addon's version so home rows refetch after a scan. */
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
            return WebDavConnectionResult.Failure(context.getString(R.string.webdav_enter_server_address))
        }
        return WebDavClient(context, http, normalizedBase, username.trim(), password.trim())
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

        val test = WebDavClient(context, http, normalizedBase, effectiveUsername, trimmedPassword)
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
        return WebDavClient(context, http, source.baseUrl, source.username, password).playbackHeaders()
    }

    /** Read from DataStore, not cached state, so a scan right after adding a source finds it. */
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
            val client = WebDavClient(context, http, source.baseUrl, source.username, password)
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
                            errorMessage = error.message
                                ?: context.getString(R.string.webdav_scan_could_not_finish)
                        )
                    }
                }
            )
        }
    }

    /** Folders the server confirms are gone; a short listing skips the prune rather than deleting. */
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

    /** Rescans enabled sources once per process; later calls do nothing. */
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

    /** Clears a source's index and rescans, for when scan rules change and unchanged folders would be reused. */
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
        val match = releaseMatcher.match(parsed, folder.files.map { it.fileName }) ?: return null

        return WebDavMatch(
            folderKey = folder.key,
            sourceId = source.id,
            folderPath = folder.path,
            contentId = match.contentId,
            contentType = match.contentType,
            title = match.title,
            poster = match.poster,
            metaName = match.meta?.name,
            metaPoster = match.meta?.poster,
            season = match.season,
            episodeOffset = match.episodeOffset,
            step = match.step.name,
            confidence = match.confidence
        )
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

    suspend fun searchForOverride(query: String): List<AnimeSearchHit> = releaseMatcher.search(query)

    /** Applies a manual correction. Rescans never overwrite it. */
    suspend fun applyOverride(
        folderKey: String,
        hit: AnimeSearchHit,
        season: Int?,
        treatAsMovie: Boolean
    ): Result<WebDavMatch> {
        val sourceId = folderKey.substringBefore('|')
        val folderPath = folderKey.substringAfter('|')
        val resolved = releaseMatcher.resolveHit(hit, treatAsMovie)
            ?: return Result.failure(IllegalStateException("No id mapping exists for ${hit.title}."))

        val meta = resolved.meta
        val match = WebDavMatch(
            folderKey = folderKey,
            sourceId = sourceId,
            folderPath = folderPath,
            contentId = resolved.contentId,
            contentType = resolved.contentType,
            title = hit.title,
            poster = hit.poster,
            metaName = meta?.name,
            metaPoster = meta?.poster,
            season = season ?: resolved.armSeason,
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
        /** A listing shorter than this fraction of the last one is treated as truncated. */
        const val LISTING_SHRINK_FLOOR = 0.8

        /** Upper bound on the folders one scan may question, so a bad listing cannot storm. */
        const val MAX_DELETION_CHECKS = 20
    }
}

/** What one source currently holds, for the settings rows. */
internal data class WebDavSourceCounts(
    val folders: Int = 0,
    val files: Int = 0,
    val matched: Int = 0
)
