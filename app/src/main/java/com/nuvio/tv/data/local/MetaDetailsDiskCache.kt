package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.TmdbSettings
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk copy of a detail screen's fully composed metadata — the addon's meta with TMDB
 * enrichment and episode names already merged in. Reopening a title reads this instead of
 * refetching, so the loading skeleton is skipped entirely.
 */
@Singleton
class MetaDetailsDiskCache @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi
) {
    companion object {
        private const val TAG = "MetaDetailsCache"
        private const val DIRECTORY = "meta_details_cache"

        /** Bumped whenever the envelope or the composed meta changes shape. */
        internal const val SCHEMA_VERSION = 1

        /** Airing titles are served from disk and refreshed behind the screen past this age. */
        const val ONGOING_REFRESH_AFTER_MS = 6L * 60 * 60 * 1000

        /** Even a finished title re-syncs eventually, in case its metadata was corrected. */
        const val MAX_TRUSTED_AGE_MS = 30L * 24 * 60 * 60 * 1000

        private const val MAX_CACHE_BYTES = 20L * 1024 * 1024

        /** Enough that stepping between a few titles never re-parses. */
        private const val MEMORY_ENTRIES = 6

        /**
         * Leading chars of a file name, derived from id and type alone. Home can test for a stored
         * copy from those two without rebuilding the settings half of the key.
         */
        private const val PREFIX_LENGTH = 16

        /** Sweeping down to a fraction of the cap keeps eviction from running on every write. */
        private const val SWEEP_TARGET_RATIO = 0.85

        private val FINISHED_STATUSES = setOf("ended", "canceled", "cancelled", "released")

        /**
         * Only a status that says the title is done earns a no-network open. An unknown status
         * counts as still airing, so a missing one costs a background refresh, never a stale
         * episode list.
         */
        internal fun isFinished(status: String?): Boolean =
            status?.trim()?.lowercase() in FINISHED_STATUSES
    }

    private val adapter = moshi.adapter(CachedMetaEnvelope::class.java)
    private val mutex = Mutex()
    private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Skips rewriting a file whose contents the screen has not actually changed. */
    private val lastWrittenHashes = HashMap<String, Int>()

    /**
     * Which titles have a stored copy, by id-and-type prefix. Built from one directory listing and
     * kept in memory so the home screen can ask per focused poster without touching the disk.
     */
    private val storedPrefixes: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var indexLoaded = false

    /** Reopening a title in the same session skips the parse as well as the network. */
    private val memoryCache: MutableMap<String, CachedMetaDetails> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedMetaDetails>(MEMORY_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CachedMetaDetails>?
            ): Boolean = size > MEMORY_ENTRIES
        }
    )

    /**
     * Every input that changes what the composed meta looks like. A toggle flip lands on a
     * different key, so it misses and refetches rather than serving an incomplete copy. The addon
     * a title was opened from is left out: the screen asks the meta addons first, so one copy per
     * title serves every row it appears in.
     */
    fun keyFor(
        itemId: String,
        itemType: String,
        preferExternalMetaAddon: Boolean,
        tmdbSettings: TmdbSettings
    ): String {
        val variant = buildString {
            append("v").append(SCHEMA_VERSION)
            append('|').append(preferExternalMetaAddon)
            append('|').append(tmdbSignature(tmdbSettings))
        }
        return "${idPrefix(itemId, itemType)}_${sha256(variant)}"
    }

    /**
     * Whether any copy of this title is stored, whatever settings produced it. Deliberately not a
     * freshness test: a stale copy is refreshed by the detail screen itself, and this answers from
     * one directory listing, without opening or parsing a file.
     */
    suspend fun hasEntryFor(itemId: String, itemType: String): Boolean = withContext(Dispatchers.IO) {
        loadIndexOnce()
        idPrefix(itemId, itemType) in storedPrefixes
    }

    private suspend fun loadIndexOnce() {
        if (indexLoaded) return
        mutex.withLock {
            if (indexLoaded) return@withLock
            directory().listFiles()?.forEach { file ->
                val prefix = file.name.substringBefore('_', missingDelimiterValue = "")
                if (prefix.length == PREFIX_LENGTH) {
                    storedPrefixes.add(prefix)
                } else {
                    // Written before the name carried a prefix; nothing will ever match it.
                    file.delete()
                }
            }
            indexLoaded = true
        }
    }

    suspend fun read(key: String): CachedMetaDetails? = withContext(Dispatchers.IO) {
        memoryCache[key]?.let { return@withContext it }
        val file = fileFor(key)
        if (!file.exists()) return@withContext null
        val envelope = try {
            adapter.fromJson(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Discarding unreadable entry: ${e.message}")
            file.delete()
            return@withContext null
        }
        if (envelope == null || envelope.version != SCHEMA_VERSION) {
            file.delete()
            return@withContext null
        }
        // Touching on read is what makes eviction least-recently-used rather than oldest-written.
        file.setLastModified(System.currentTimeMillis())
        CachedMetaDetails(
            meta = envelope.meta,
            tmdbRating = envelope.tmdbRating,
            collectionId = envelope.collectionId,
            collectionName = envelope.collectionName,
            savedAtMs = envelope.savedAtMs
        ).also { memoryCache[key] = it }
    }

    suspend fun write(
        key: String,
        meta: Meta,
        tmdbRating: Float?,
        collectionId: Int?,
        collectionName: String?
    ) = withContext(Dispatchers.IO) {
        val payload = meta.withoutStreams()
        val contentHash = listOf(payload, tmdbRating, collectionId, collectionName).hashCode()
        mutex.withLock {
            if (lastWrittenHashes[key] == contentHash) return@withLock
            val savedAtMs = System.currentTimeMillis()
            val envelope = CachedMetaEnvelope(
                version = SCHEMA_VERSION,
                savedAtMs = savedAtMs,
                tmdbRating = tmdbRating,
                collectionId = collectionId,
                collectionName = collectionName,
                meta = payload
            )
            val target = fileFor(key)
            val temp = File(target.parentFile, "${target.name}.tmp")
            try {
                temp.writeText(adapter.toJson(envelope))
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return@withLock
                }
                lastWrittenHashes[key] = contentHash
                storedPrefixes.add(key.substringBefore('_'))
                memoryCache[key] = CachedMetaDetails(
                    meta = payload,
                    tmdbRating = tmdbRating,
                    collectionId = collectionId,
                    collectionName = collectionName,
                    savedAtMs = savedAtMs
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write ${meta.id}: ${e.message}")
                temp.delete()
                return@withLock
            }
            evictIfOverBudget()
        }
    }

    /**
     * Fire-and-forget so the existing non-suspending cache-clear paths can call it. Anything
     * that invalidates addon metadata invalidates these copies too.
     */
    fun clearAsync() {
        clearScope.launch {
            mutex.withLock {
                lastWrittenHashes.clear()
                memoryCache.clear()
                storedPrefixes.clear()
                indexLoaded = true
                directory().listFiles()?.forEach { it.delete() }
            }
        }
    }

    private fun evictIfOverBudget() {
        val files = directory().listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        val target = (MAX_CACHE_BYTES * SWEEP_TARGET_RATIO).toLong()
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= target) return
            val size = file.length()
            if (file.delete()) {
                total -= size
                // A prefix can cover several variants, so rebuild rather than guess.
                indexLoaded = false
            }
        }
    }

    private fun directory(): File =
        File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    private fun fileFor(key: String): File = File(directory(), "$key.json")

    private fun tmdbSignature(settings: TmdbSettings): String {
        if (!settings.enabled) return "tmdb:off"
        // Only the groups that end up on the meta object — recommendations and collections
        // are fetched separately and would fragment the cache for nothing.
        val flags = listOf(
            settings.useArtwork,
            settings.useBasicInfo,
            settings.useDetails,
            settings.useReleaseDates,
            settings.useCredits,
            settings.useProductions,
            settings.useNetworks,
            settings.useEpisodes,
            settings.useTrailers
        ).joinToString("") { if (it) "1" else "0" }
        return "tmdb:${settings.language.trim().lowercase()}:$flags"
    }

    private fun idPrefix(itemId: String, itemType: String): String =
        sha256(itemId.trim() + "|" + itemType.trim().lowercase()).take(PREFIX_LENGTH)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/** Streams expire and the stream screen fetches its own meta, so they are never stored. */
private fun Meta.withoutStreams(): Meta {
    if (videos.none { it.streams.isNotEmpty() }) return this
    return copy(videos = videos.map { if (it.streams.isEmpty()) it else it.copy(streams = emptyList()) })
}

internal data class CachedMetaEnvelope(
    val version: Int,
    val savedAtMs: Long,
    val tmdbRating: Float?,
    val collectionId: Int?,
    val collectionName: String?,
    val meta: Meta
)

data class CachedMetaDetails(
    val meta: Meta,
    val tmdbRating: Float?,
    val collectionId: Int?,
    val collectionName: String?,
    val savedAtMs: Long
) {
    fun needsRefresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        val age = nowMs - savedAtMs
        if (age >= MetaDetailsDiskCache.MAX_TRUSTED_AGE_MS) return true
        if (!MetaDetailsDiskCache.isFinished(meta.status)) {
            return age >= MetaDetailsDiskCache.ONGOING_REFRESH_AFTER_MS
        }
        return false
    }
}
