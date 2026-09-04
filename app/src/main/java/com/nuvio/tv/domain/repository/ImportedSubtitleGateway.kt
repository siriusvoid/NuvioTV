package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitleMatch
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Imported subtitle files, offered to the player next to the addons' subtitles under a local url. */
interface ImportedSubtitleGateway {

    val packs: StateFlow<List<ImportedSubtitlePack>>

    fun packsFor(metaId: String): List<ImportedSubtitlePack>

    /** Imports [folder]'s subtitle files onto [meta]'s episodes; returns how many it took. */
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
