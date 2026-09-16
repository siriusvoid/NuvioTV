package com.nuvio.tv.data.subtitles

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.player.SubtitleCharsetDetector
import com.nuvio.tv.data.local.ImportedSubtitleIndex
import com.nuvio.tv.data.local.ImportedSubtitlePreferences
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitleMatch
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import com.nuvio.tv.domain.model.subtitles.SubtitleFolderSource
import com.nuvio.tv.domain.model.subtitles.SubtitleScanProgress
import com.nuvio.tv.domain.model.subtitles.UnmatchedSubtitleFolder
import com.nuvio.tv.domain.repository.ImportedSubtitleGateway
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owns subtitle folders and their scans; scans run on the app scope so they outlive the screen. */
@Singleton
internal class ImportedSubtitleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ImportedSubtitlePreferences,
    private val scanner: ImportedSubtitleScanner
) : ImportedSubtitleGateway {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanJobs = ConcurrentHashMap<String, Job>()

    private val _sources = MutableStateFlow<List<SubtitleFolderSource>>(emptyList())
    override val sources: StateFlow<List<SubtitleFolderSource>> = _sources.asStateFlow()

    private val _packs = MutableStateFlow<List<ImportedSubtitlePack>>(emptyList())
    override val packs: StateFlow<List<ImportedSubtitlePack>> = _packs.asStateFlow()

    private val _unmatched = MutableStateFlow<List<UnmatchedSubtitleFolder>>(emptyList())
    override val unmatched: StateFlow<List<UnmatchedSubtitleFolder>> = _unmatched.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, SubtitleScanProgress>>(emptyMap())
    override val progress: StateFlow<Map<String, SubtitleScanProgress>> = _progress.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    init {
        scope.launch {
            preferences.index.collect { stored ->
                _sources.value = stored.sources
                _packs.value = stored.packs
                _unmatched.value = stored.unmatched
                _loaded.value = true
            }
        }
    }

    override suspend fun addSource(path: String, displayName: String): Result<SubtitleFolderSource> {
        val folder = File(path)
        if (!folder.isDirectory) {
            return Result.failure(IllegalStateException(context.getString(R.string.subtitle_folders_not_readable)))
        }
        val index = preferences.current()
        if (index.sources.any { it.path == folder.absolutePath }) {
            return Result.failure(IllegalStateException(context.getString(R.string.subtitle_folders_already_added)))
        }

        val source = SubtitleFolderSource(
            id = "subs-${System.currentTimeMillis()}",
            path = folder.absolutePath,
            displayName = displayName.ifBlank { folder.name.ifBlank { folder.absolutePath } }
        )
        preferences.save(index.copy(sources = index.sources + source))
        rescan(source.id)
        return Result.success(source)
    }

    override suspend fun removeSource(sourceId: String) {
        scanJobs.remove(sourceId)?.cancel()
        val index = preferences.current()
        // Only the index goes: the files belong to the user, not to Nuvio.
        preferences.save(
            ImportedSubtitleIndex(
                sources = index.sources.filterNot { it.id == sourceId },
                packs = index.packs.filterNot { it.sourceId == sourceId },
                unmatched = index.unmatched.filterNot { it.sourceId == sourceId }
            )
        )
        _progress.update { it - sourceId }
    }

    override suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        val index = preferences.current()
        preferences.save(
            index.copy(
                sources = index.sources.map { source ->
                    if (source.id == sourceId) source.copy(enabled = enabled) else source
                }
            )
        )
    }

    override fun rescan(sourceId: String) {
        if (scanJobs[sourceId]?.isActive == true) return
        scanJobs[sourceId] = scope.launch {
            awaitLoaded()
            val source = preferences.current().sources.firstOrNull { it.id == sourceId }
                ?: return@launch
            runScan(source)
        }
    }

    override fun rescanAll() {
        scope.launch {
            awaitLoaded()
            preferences.current().sources.forEach { rescan(it.id) }
        }
    }

    private suspend fun runScan(source: SubtitleFolderSource) {
        publishProgress(source.id, SubtitleScanProgress.Scanning(foldersFound = 0))
        val result = runCatching {
            scanner.scan(source) { progress -> publishProgress(source.id, progress) }
        }.getOrElse { error ->
            Log.w(TAG, "Scan of ${source.path} failed", error)
            publishProgress(
                source.id,
                SubtitleScanProgress.Failed(
                    error.message ?: context.getString(R.string.library_scan_failed_fallback)
                )
            )
            return
        }

        val index = preferences.current()
        preferences.save(
            index.copy(
                sources = index.sources.map { existing ->
                    if (existing.id == source.id) {
                        existing.copy(lastScanAt = System.currentTimeMillis())
                    } else {
                        existing
                    }
                },
                packs = index.packs.filterNot { it.sourceId == source.id } + result.packs,
                unmatched = index.unmatched.filterNot { it.sourceId == source.id } + result.unmatched
            )
        )
        publishProgress(source.id, SubtitleScanProgress.Idle)
    }

    private fun publishProgress(sourceId: String, progress: SubtitleScanProgress) {
        _progress.update { it + (sourceId to progress) }
    }

    override suspend fun subtitlesFor(
        videoId: String,
        metaId: String?,
        season: Int?,
        episode: Int?
    ): List<ImportedSubtitleMatch> {
        awaitLoaded()
        val enabledSources = _sources.value.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
        return _packs.value
            .filter { it.sourceId in enabledSources }
            .flatMap { pack ->
                pack.files
                    .filter { file ->
                        when {
                            file.videoId == videoId -> true
                            metaId == null || pack.metaId != metaId -> false
                            season == null || episode == null -> false
                            else -> file.season == season && file.episode == episode
                        }
                    }
                    .filter { File(it.path).isFile }
                    .map { file -> ImportedSubtitleMatch(pack = pack, file = file) }
            }
    }

    override fun subtitleUrl(path: String): String = Uri.fromFile(File(path)).toString()

    /** Only paths this library published are opened; the player hands back any url it was given. */
    override fun readSubtitleText(url: String): String? {
        val indexed = indexedFile(url) ?: return null
        return try {
            val file = File(indexed.first)
            if (!file.isFile) return null
            SubtitleCharsetDetector.decode(file.readBytes(), languageHint = indexed.second)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $url", e)
            null
        }
    }

    /** The indexed path a published url names, with the pack's language. */
    private fun indexedFile(url: String): Pair<String, String>? {
        if (!url.startsWith("file:", ignoreCase = true) && !url.startsWith("/")) return null
        val path = runCatching { Uri.parse(url).path }.getOrNull() ?: url
        return _packs.value.firstNotNullOfOrNull { pack ->
            pack.files.firstOrNull { it.path == path }?.let { path to pack.language }
        }
    }

    private suspend fun awaitLoaded() {
        _loaded.first { it }
    }

    private companion object {
        const val TAG = "ImportedSubtitles"
    }
}
