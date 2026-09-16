package com.nuvio.tv.data.webdav

import android.content.Context
import android.util.Log
import com.nuvio.tv.domain.model.webdav.WebDavFolder
import com.nuvio.tv.domain.model.webdav.WebDavMatch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Split of a Stremio video id into its content id and any trailing numbers. */
internal data class VideoIdParts(
    val contentId: String,
    val season: Int?,
    val episode: Int?
)

private val PREFIXED_ID_SPACES = setOf(
    "kitsu", "mal", "myanimelist", "anilist", "anidb",
    "tmdb", "tvdb", "tvdbc", "tvmaze", "trakt", "webdav"
)

/** Parses both `tt0972656:4:1` and `kitsu:6480:1`; the addon can serve either shape. */
internal fun parseVideoId(raw: String): VideoIdParts {
    val parts = raw.split(':')
    if (parts.isEmpty()) return VideoIdParts(raw, null, null)

    val prefixed = parts.size >= 2 && parts[0].lowercase() in PREFIXED_ID_SPACES
    val baseCount = if (prefixed) 2 else 1
    val contentId = parts.take(baseCount).joinToString(":")
    val trailing = parts.drop(baseCount).mapNotNull { it.toIntOrNull() }

    return when (trailing.size) {
        0 -> VideoIdParts(contentId, null, null)
        1 -> VideoIdParts(contentId, null, trailing[0])
        else -> VideoIdParts(contentId, trailing[0], trailing[1])
    }
}

/** Accumulated folders and matches, file-backed; a folder leaves only when the server confirms deletion. */
@Singleton
internal class WebDavIndex @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()
    private val foldersBySource = mutableMapOf<String, List<WebDavFolder>>()
    private val matchesByKey = mutableMapOf<String, WebDavMatch>()
    private var matchesLoaded = false
    private var reverseIndex: Map<String, List<String>>? = null

    /** Folders by key, rebuilt lazily so a stream lookup costs the answer, not the library size. */
    private var foldersByKey: Map<String, WebDavFolder>? = null

    private val rootDir: File by lazy {
        File(context.filesDir, "webdav").also { it.mkdirs() }
    }

    /** Sources the index may load on demand, so a catalogue request can reach one not yet scanned. */
    private var knownSourceIds: List<String> = emptyList()

    /** Also prunes removed sources: the index isn't profile-scoped, so a switch would leak folders. */
    suspend fun publishSourceIds(ids: List<String>) {
        onIo {
            knownSourceIds = ids
            val stale = foldersBySource.keys - ids.toSet()
            if (stale.isNotEmpty()) {
                stale.forEach(foldersBySource::remove)
                invalidateLocked()
            }
        }
    }

    suspend fun folders(sourceId: String): List<WebDavFolder> = onIo {
        loadFoldersLocked(sourceId)
    }

    /** Merges a scan window; only folders in [deletedPaths] are removed. */
    suspend fun mergeFolders(
        sourceId: String,
        scanned: List<WebDavFolder>,
        deletedPaths: Set<String> = emptySet()
    ) {
        onIo {
            loadMatchesLocked()
            val existing = loadFoldersLocked(sourceId)
            val byPath = LinkedHashMap<String, WebDavFolder>(existing.size + scanned.size)
            val dropped = ArrayList<String>()

            existing.forEach { folder ->
                if (folder.path in deletedPaths) dropped.add(folder.key) else byPath[folder.path] = folder
            }
            scanned.forEach { byPath[it.path] = it }

            val merged = byPath.values.toList()
            foldersBySource[sourceId] = merged
            invalidateLocked()
            persistFoldersLocked(sourceId, merged)

            if (dropped.isNotEmpty()) {
                dropped.forEach(matchesByKey::remove)
                persistMatchesLocked()
            }
        }
    }

    suspend fun deleteSource(sourceId: String) {
        onIo {
            foldersBySource.remove(sourceId)
            matchesByKey.values
                .filter { it.sourceId == sourceId }
                .map { it.folderKey }
                .forEach(matchesByKey::remove)
            invalidateLocked()
            fileFor(sourceId).delete()
            persistMatchesLocked()
        }
    }

    suspend fun matches(): Map<String, WebDavMatch> = onIo {
        loadMatchesLocked()
        matchesByKey.toMap()
    }

    suspend fun match(folderKey: String): WebDavMatch? = onIo {
        loadMatchesLocked()
        matchesByKey[folderKey]
    }

    suspend fun putMatch(match: WebDavMatch) = putMatches(listOf(match))

    suspend fun putMatches(matches: List<WebDavMatch>) {
        if (matches.isEmpty()) return
        onIo {
            loadMatchesLocked()
            matches.forEach { matchesByKey[it.folderKey] = it }
            invalidateLocked()
            persistMatchesLocked()
        }
    }

    suspend fun removeMatch(folderKey: String) {
        onIo {
            loadMatchesLocked()
            matchesByKey.remove(folderKey)
            invalidateLocked()
            persistMatchesLocked()
        }
    }

    /** Folders that resolved to [contentId], with their match. Used on every stream request. */
    suspend fun foldersForContentId(contentId: String): List<Pair<WebDavFolder, WebDavMatch>> =
        onIo {
            loadMatchesLocked()
            val index = reverseIndex ?: buildReverseIndexLocked()
            val folderKeys = index[contentId].orEmpty()
            val allFolders = if (folderKeys.isEmpty()) emptyMap() else foldersByKeyLocked()
            folderKeys.mapNotNull { key ->
                val match = matchesByKey[key] ?: return@mapNotNull null
                if (match.excluded) return@mapNotNull null
                val folder = allFolders[match.folderKey] ?: return@mapNotNull null
                folder to match
            }
        }

    /** Matched items deduplicated by content id, newest torrent first. */
    suspend fun catalogEntries(sourceId: String?, contentType: String? = null): List<WebDavMatch> =
        onIo {
            loadMatchesLocked()
            ensureAllSourcesLoadedLocked()
            val folders = foldersByKeyLocked()

            fun modifiedAtOf(match: WebDavMatch): Long =
                folders[match.folderKey]?.modifiedAt ?: Long.MIN_VALUE

            matchesByKey.values
                .asSequence()
                .filter { !it.excluded }
                .filter { contentType == null || it.contentType == contentType }
                .filter { sourceId == null || it.sourceId == sourceId }
                .groupBy { it.contentId }
                .map { (_, group) -> group.maxBy { modifiedAtOf(it) } }
                .sortedWith(
                    compareByDescending<WebDavMatch> { modifiedAtOf(it) }
                        .thenBy { it.title.lowercase() }
                )
                .toList()
        }

    /** One place for the lock and disk dispatch every entry point needs. */
    private suspend fun <T> onIo(block: () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun invalidateLocked() {
        reverseIndex = null
        foldersByKey = null
    }

    private fun foldersByKeyLocked(): Map<String, WebDavFolder> =
        foldersByKey ?: foldersBySource.values
            .flatten()
            .associateBy { it.key }
            .also { foldersByKey = it }

    private fun loadFoldersLocked(sourceId: String): List<WebDavFolder> {
        foldersBySource[sourceId]?.let { return it }
        val payload = readFromDisk(sourceId)
        val folders = if (payload.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<WebDavFolder>>(payload) }
                .getOrElse { error ->
                    Log.w(TAG, "Could not read the index for $sourceId — starting empty", error)
                    emptyList()
                }
        }
        foldersBySource[sourceId] = folders
        foldersByKey = null
        return folders
    }

    private fun ensureAllSourcesLoadedLocked() {
        knownSourceIds.forEach { sourceId ->
            if (!foldersBySource.containsKey(sourceId)) loadFoldersLocked(sourceId)
        }
    }

    private fun persistFoldersLocked(sourceId: String, folders: List<WebDavFolder>) {
        runCatching { writeToDisk(sourceId, json.encodeToString(folders)) }
            .onFailure { Log.w(TAG, "Could not persist the index for $sourceId", it) }
    }

    private fun loadMatchesLocked() {
        if (matchesLoaded) return
        matchesLoaded = true
        val payload = matchesFile().takeIf { it.exists() }?.let {
            runCatching { it.readText() }.getOrNull()
        }
        if (payload.isNullOrBlank()) return
        runCatching { json.decodeFromString<List<WebDavMatch>>(payload) }
            .onSuccess { stored -> stored.forEach { matchesByKey[it.folderKey] = it } }
            .onFailure { Log.w(TAG, "Could not read stored matches", it) }
    }

    private fun persistMatchesLocked() {
        runCatching {
            writeAtomically(matchesFile(), json.encodeToString(matchesByKey.values.toList()))
        }.onFailure { Log.w(TAG, "Could not persist matches", it) }
    }

    private fun buildReverseIndexLocked(): Map<String, List<String>> {
        ensureAllSourcesLoadedLocked()
        val index = matchesByKey.values
            .filterNot { it.excluded }
            .groupBy { it.contentId }
            .mapValues { (_, matches) -> matches.map { it.folderKey } }
        reverseIndex = index
        return index
    }

    private fun fileFor(sourceId: String): File = File(rootDir, "${safeKey(sourceId)}.json")

    private fun matchesFile(): File = File(rootDir, "matches.json")

    private fun readFromDisk(sourceId: String): String? =
        fileFor(sourceId).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    private fun writeToDisk(sourceId: String, payload: String) =
        writeAtomically(fileFor(sourceId), payload)

    /** Write to a sibling then rename, so a kill mid-write cannot truncate the index. */
    private fun writeAtomically(file: File, payload: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText(payload)
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        } catch (t: Throwable) {
            temporary.delete()
            throw t
        }
    }

    private fun safeKey(value: String): String = value.map { character ->
        if (character.isLetterOrDigit() || character == '_' || character == '-') character else '_'
    }.joinToString("")

    private companion object {
        const val TAG = "WebDavIndex"
    }
}
