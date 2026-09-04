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

/**
 * Video ids differ by id space: Cinemeta-style ids carry three parts
 * (`tt0972656:4:1`) while anime addons carry two (`kitsu:6480:1`, episode with
 * no season). Both shapes have to parse, since the installed metadata addon can
 * serve either depending on how it is configured.
 */
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

/**
 * The scanned folders and their resolved matches.
 *
 * The window refreshes but the index accumulates: folders stay after they drop
 * out of the newest 50, so the library is not limited to what one scan can reach.
 *
 * Nothing here infers a deletion: a folder leaves only when the scanner has
 * confirmed it with the server, so a listing that quietly omits entries can never
 * remove anything.
 *
 * Like the local library index this is file-backed rather than DataStore-backed:
 * Preferences DataStore rewrites its whole file on every commit, which is wrong
 * for an index that grows to megabytes.
 */
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

    /**
     * Every folder by [WebDavFolder.key], across the sources loaded so far. Rebuilt
     * lazily beside [reverseIndex] and dropped by the same writes: a stream request
     * resolves a handful of keys, and flattening the whole index to do it made the
     * lookup cost grow with the library rather than with the answer.
     */
    private var foldersByKey: Map<String, WebDavFolder>? = null

    private val rootDir: File by lazy {
        File(context.filesDir, "webdav").also { it.mkdirs() }
    }

    /**
     * Source ids the index may load from disk on demand. Published by the manager
     * whenever the configured sources change, so a catalogue request can reach a
     * source this process has not scanned yet.
     */
    private var knownSourceIds: List<String> = emptyList()

    /**
     * Also drops what is cached for sources that are gone. The stores are
     * profile-scoped but this index is not, so without the prune a profile switch
     * would leave the previous profile's folders answering stream requests.
     */
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

    /**
     * Merges a scan window into the accumulated index.
     *
     * [deletedPaths] are folders the server has confirmed are gone; their matches go
     * with them. Everything else is kept, whether or not this scan saw it.
     */
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

    /**
     * Every matched item, deduplicated by content id — the catalogue rows, newest
     * torrent first so recent additions are at the front.
     */
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

    /**
     * Every entry point holds the same lock and touches the disk, so both are done
     * in one place rather than remembered at each call site.
     */
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
