package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk cache for IMDb <-> TMDB id mappings.
 *
 * These were memory-only, so every cold start re-paid a `find` request per item before any
 * enrichment could begin — for an answer that cannot change. A title's IMDb id maps to the same
 * TMDB id forever, so this is cached without a TTL, unlike the enrichment it precedes.
 *
 * Not profile-scoped: an id mapping is a fact about the title, not about who is watching.
 */
@Singleton
class TmdbIdMappingStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Ids are stored as strings: R8 strips generic signatures, and Gson then reads a JSON number
     *  back as Double, which blew up on the first cache hit. */
    data class Snapshot(
        val imdbToTmdb: Map<String, String> = emptyMap(),
        val tmdbToImdb: Map<String, String> = emptyMap()
    )

    private val gson = Gson()
    private val mutex = Mutex()

    @Volatile private var lastWriteMs = 0L
    @Volatile private var lastWrittenHash = 0

    private fun file(): File {
        val dir = File(context.filesDir, "tmdb_ids")
        if (!dir.exists()) dir.mkdirs()
        // v2 because v1 stored ids as JSON numbers, which read back as Double under R8.
        runCatching { File(dir, "id_mappings.json").takeIf(File::exists)?.delete() }
        return File(dir, "id_mappings_v2.json")
    }

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val file = file()
                if (!file.exists()) return@runCatching Snapshot()
                gson.fromJson<Snapshot>(
                    file.readText(),
                    object : TypeToken<Snapshot>() {}.type
                ) ?: Snapshot()
            }.getOrElse { error ->
                Log.w(TAG, "Could not read id mappings: ${error.message}")
                Snapshot()
            }
        }
    }

    /**
     * Throttled and content-addressed: a burst of lookups while a row is being browsed writes once,
     * and an unchanged snapshot writes not at all. A write that is throttled away is not retried —
     * losing one costs a `find` request on some later run, which is what this saves, not what it
     * guarantees.
     */
    suspend fun save(snapshot: Snapshot) = withContext(Dispatchers.IO) {
        val trimmed = snapshot.takeIf {
            it.imdbToTmdb.size <= MAX_ENTRIES && it.tmdbToImdb.size <= MAX_ENTRIES
        } ?: Snapshot(
            imdbToTmdb = snapshot.imdbToTmdb.entries.take(MAX_ENTRIES).associate { it.key to it.value },
            tmdbToImdb = snapshot.tmdbToImdb.entries.take(MAX_ENTRIES).associate { it.key to it.value }
        )
        val hash = trimmed.hashCode()
        val now = System.currentTimeMillis()
        mutex.withLock {
            if (hash == lastWrittenHash) return@withContext
            if (now - lastWriteMs < THROTTLE_MS) return@withContext
            runCatching {
                file().writeText(gson.toJson(trimmed))
                lastWriteMs = now
                lastWrittenHash = hash
            }.onFailure { error ->
                Log.w(TAG, "Could not persist id mappings: ${error.message}")
            }
        }
    }

    private companion object {
        const val TAG = "TmdbIdStore"
        const val THROTTLE_MS = 5_000L

        /**
         * A hard ceiling rather than an eviction policy. The maps have no access order to evict by,
         * and at this size the file is still small, so anything past it is simply not persisted —
         * those titles keep working from the in-memory cache for the rest of the session.
         */
        const val MAX_ENTRIES = 4_000
    }
}
