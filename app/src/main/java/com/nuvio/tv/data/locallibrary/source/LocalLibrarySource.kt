package com.nuvio.tv.data.locallibrary.source

import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.ResolvedStream
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import kotlinx.coroutines.flow.Flow

/** A backend that enumerates media files and resolves them to playable URLs. */
sealed interface LocalLibrarySource {
    val config: LocalLibrarySourceConfig

    /** Streams discovered items. Cold flow — collection triggers the scan. */
    fun scan(): Flow<ScannedItem>

    /** Produces a URL/headers ExoPlayer can play. Null if the item is no longer reachable. */
    suspend fun resolveStream(item: ScannedItem): ResolvedStream?

    /** Best-effort connectivity / credential check used by the Add Source UI. */
    suspend fun testConnection(): Result<Unit>
}
