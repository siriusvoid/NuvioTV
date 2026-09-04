package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.subtitles.ImportedSubtitleMatch
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import com.nuvio.tv.domain.model.subtitles.SubtitleFolderSource
import com.nuvio.tv.domain.model.subtitles.SubtitleScanProgress
import com.nuvio.tv.domain.model.subtitles.UnmatchedSubtitleFolder
import kotlinx.coroutines.flow.StateFlow

/**
 * The library of subtitle folders the user pointed Nuvio at.
 *
 * Scanned and matched the way the WebDAV and local libraries are, and offered to
 * the player alongside the addons' own subtitles. Files are read where they lie:
 * nothing here ever copies, moves or deletes one.
 */
interface ImportedSubtitleGateway {

    val sources: StateFlow<List<SubtitleFolderSource>>

    val packs: StateFlow<List<ImportedSubtitlePack>>

    /** Releases the scanner found but could not place, so they are not lost silently. */
    val unmatched: StateFlow<List<UnmatchedSubtitleFolder>>

    val progress: StateFlow<Map<String, SubtitleScanProgress>>

    suspend fun addSource(path: String, displayName: String): Result<SubtitleFolderSource>

    suspend fun removeSource(sourceId: String)

    suspend fun setEnabled(sourceId: String, enabled: Boolean)

    /** Rescans in the background; progress arrives through [progress]. */
    fun rescan(sourceId: String)

    fun rescanAll()

    /** Imported subtitles that answer for one episode. */
    suspend fun subtitlesFor(
        videoId: String,
        metaId: String?,
        season: Int?,
        episode: Int?
    ): List<ImportedSubtitleMatch>

    /** The url a stored subtitle is served to the player under. */
    fun subtitleUrl(path: String): String

    /** Decoded text of an indexed subtitle, or null when [url] is not one of ours. */
    fun readSubtitleText(url: String): String?
}
