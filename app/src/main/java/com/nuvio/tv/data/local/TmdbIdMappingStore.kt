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

/** IMDb to TMDB id mappings on disk with no TTL, since they never change. Not profile-scoped. */
@Singleton
class TmdbIdMappingStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Ids are strings: under R8, Gson reads JSON numbers back as Double. */
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
        // v1 stored ids as numbers, which read back as Double under R8.
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

    /** Throttled and skipped when unchanged; a dropped write only costs a later lookup. */
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

        /** A hard cap, not eviction: past it, mappings stay in memory only. */
        const val MAX_ENTRIES = 4_000
    }
}
