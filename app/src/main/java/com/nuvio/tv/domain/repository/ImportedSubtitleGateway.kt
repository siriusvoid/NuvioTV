package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitleMatch
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The library of subtitle files the user imported from storage on this device.
 *
 * Imported files are offered to the player alongside the addons' own subtitles,
 * so nothing downstream has to know where a subtitle came from — only that its
 * url names a local file, which [readSubtitleText] answers for.
 */
interface ImportedSubtitleGateway {

    val packs: StateFlow<List<ImportedSubtitlePack>>

    fun packsFor(metaId: String): List<ImportedSubtitlePack>

    /**
     * Copies every subtitle file in [folder] in and places them on [meta]'s
     * episodes. Returns how many files were taken; zero means the folder held no
     * subtitle file this app can use.
     */
    suspend fun import(meta: Meta, folder: File): Int

    suspend fun setKeepAfterWatching(packId: String, keep: Boolean)

    suspend fun deletePack(packId: String)

    /** Imported subtitles that answer for one episode. */
    suspend fun subtitlesFor(
        videoId: String,
        metaId: String?,
        season: Int?,
        episode: Int?
    ): List<ImportedSubtitleMatch>

    /** The url a stored subtitle is served to the player under. */
    fun subtitleUrl(relativePath: String): String

    /** Decoded text of a stored subtitle, or null when [url] is not one of ours. */
    fun readSubtitleText(url: String): String?
}
