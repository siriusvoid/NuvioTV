package com.nuvio.tv.data.locallibrary

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.data.local.LocalLibraryPreferences
import com.nuvio.tv.data.local.MatchOverrideStore
import com.nuvio.tv.data.locallibrary.match.FilenameParser
import com.nuvio.tv.data.locallibrary.match.MediaMatcher
import com.nuvio.tv.data.locallibrary.source.LocalLibrarySourceFactory
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.LocalMatch
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owns local sources and their scans; scans run on the app scope so they outlive the screen. */
@Singleton
class LocalLibraryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: LocalLibraryPreferences,
    private val index: LocalLibraryIndex,
    private val overrideStore: MatchOverrideStore,
    private val matcher: MediaMatcher,
    private val sourceFactory: LocalLibrarySourceFactory,
    private val metaRepository: MetaRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanJobs = ConcurrentHashMap<String, Job>()
    private val rescanMutex = Mutex()

    private val _scanProgress = MutableStateFlow<Map<String, ScanProgress>>(emptyMap())
    val scanProgress: StateFlow<Map<String, ScanProgress>> = _scanProgress.asStateFlow()

    val sources: StateFlow<List<LocalLibrarySourceConfig>>
        get() = sourcesFlow

    // Eager StateFlow so screens read sources without waiting on DataStore.
    private val sourcesFlow: MutableStateFlow<List<LocalLibrarySourceConfig>> = MutableStateFlow(emptyList())

    init {
        scope.launch {
            preferences.sources.collect { sourcesFlow.value = it }
        }
    }

    suspend fun addLocalFile(
        displayName: String,
        treeUri: String
    ): Result<LocalLibrarySourceConfig> = runCatching {
        val id = generateSourceId()
        val config = LocalLibrarySourceConfig(
            id = id,
            displayName = displayName,
            kind = com.nuvio.tv.domain.model.locallibrary.SourceKind.LOCAL_FILE,
            urlOrPath = treeUri
        )
        val source = sourceFactory.create(config)
        val test = source.testConnection()
        if (test.isFailure) {
            throw test.exceptionOrNull() ?: IllegalStateException(context.getString(R.string.local_library_folder_not_accessible))
        }
        preferences.upsert(config)
        kickoffScan(config)
        config
    }.onFailure { Log.e(TAG, "addLocalFile failed for $treeUri", it) }

    suspend fun removeSource(sourceId: String) {
        scanJobs[sourceId]?.cancel()
        scanJobs.remove(sourceId)
        preferences.remove(sourceId)
        index.deleteSource(sourceId)
        overrideStore.removeForSource(sourceId)
        metaRepository.clearCache()
    }

    suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        preferences.setEnabled(sourceId, enabled)
        metaRepository.clearCache()
    }

    /** Manually trigger a rescan from the settings UI. */
    fun rescan(sourceId: String) {
        scope.launch {
            val config = preferences.sources.first().firstOrNull { it.id == sourceId } ?: return@launch
            kickoffScan(config)
        }
    }

    /** Rescan every configured source. Scans serialize via [rescanMutex]. */
    fun rescanAll() {
        scope.launch {
            preferences.sources.first().forEach { kickoffScan(it) }
        }
    }

    /** Matches the whole folder in one write on the app scope, so closing the picker can't cut it short. */
    suspend fun matchFolder(reference: ScannedItem, tmdbId: Int, contentType: ContentType) {
        scope.launch {
            val folder = reference.relativePath.folderKey()
            // One show, so the IMDB id is resolved once and shared by every episode.
            val imdbId = matcher.resolveImdbId(tmdbId, contentType)
            val matches = index.load(reference.sourceId)
                .filter { it.relativePath.folderKey() == folder }
                .map { item ->
                    val parsed = FilenameParser.parse(item.fileName)
                    LocalMatch(
                        itemKey = item.itemKey,
                        tmdbId = tmdbId,
                        contentType = contentType,
                        season = parsed.season ?: item.parsedSeason,
                        episode = parsed.episode ?: item.parsedEpisode,
                        userSet = true,
                        imdbId = imdbId,
                        score = 1f
                    )
                }
            overrideStore.putAll(matches)
            metaRepository.clearCache()
        }.join()
    }

    private fun String.folderKey(): String =
        replace('\\', '/').substringBeforeLast('/', missingDelimiterValue = "")

    suspend fun unmatchedItems(sourceId: String): List<ScannedItem> {
        val items = index.load(sourceId)
        val matches = overrideStore.matches.first()
        return items.filter { matches[it.itemKey] == null }
    }

    suspend fun resolveItem(localId: String): ScannedItem? = index.findByLocalId(localId)

    private fun kickoffScan(config: LocalLibrarySourceConfig) {
        scanJobs[config.id]?.cancel()
        scanJobs[config.id] = scope.launch {
            rescanMutex.withLock {
                runScan(config)
            }
        }
    }

    private suspend fun runScan(config: LocalLibrarySourceConfig) {
        Log.i(TAG, "runScan start sourceId=${config.id} kind=${config.kind} url=${config.urlOrPath}")
        _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Scanning(0))
        val source = sourceFactory.create(config)
        val collected = mutableListOf<ScannedItem>()
        try {
            source.scan().toList(collected)
        } catch (t: Throwable) {
            Log.e(TAG, "Scan failed for ${config.id}", t)
            _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Failed(
                t.message ?: context.getString(R.string.library_scan_failed_fallback)
            ))
            return
        }
        Log.i(TAG, "runScan scanned sourceId=${config.id} items=${collected.size}")
        index.replace(config.id, collected)
        _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Matching(0, collected.size))

        var matched = 0
        collected.forEachIndexed { i, item ->
            runCatching { matcher.match(item) }
                .onSuccess { if (it != null) matched++ }
                .onFailure { Log.w(TAG, "Match failed for ${item.itemKey}", it) }
            if (i % 25 == 0 || i == collected.lastIndex) {
                _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Matching(i + 1, collected.size))
            }
        }
        Log.i(TAG, "runScan complete sourceId=${config.id} scanned=${collected.size} matched=$matched")
        preferences.setScanResult(config.id, collected.size, System.currentTimeMillis())
        metaRepository.clearCache()
        _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Idle(collected.size))
    }

    private fun generateSourceId(): String = java.util.UUID.randomUUID().toString()

    sealed class ScanProgress {
        data class Idle(val itemCount: Int) : ScanProgress()
        data class Scanning(val itemsFound: Int) : ScanProgress()
        data class Matching(val matched: Int, val total: Int) : ScanProgress()
        data class Failed(val reason: String) : ScanProgress()
    }

    companion object {
        private const val TAG = "LocalLibraryManager"
    }
}
