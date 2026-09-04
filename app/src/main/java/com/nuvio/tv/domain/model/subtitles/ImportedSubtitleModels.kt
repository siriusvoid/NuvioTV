package com.nuvio.tv.domain.model.subtitles

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Subtitles the user brought in from storage attached to this device.
 *
 * A pack is one import: the files of the folder picked in a single go, tied to
 * the show whose details page started the import. Nothing here syncs to an
 * account — the files live in this app's own directory.
 */
@Immutable
@Serializable
data class ImportedSubtitlePack(
    val id: String,
    /** Id in whatever space the metadata addon serves, e.g. tt0972656. */
    val metaId: String,
    val metaType: String,
    val showName: String,
    val language: String = IMPORTED_SUBTITLE_LANGUAGE,
    val importedAt: Long = 0L,
    /** Folder the files came from. Also a season hint. */
    val sourceName: String? = null,
    /** Season the anime databases place this release in, resolved once at import. */
    val mapperSeason: Int? = null,
    /** Opts the pack out of removal once the show is watched through. */
    val keepAfterWatching: Boolean = false,
    val files: List<ImportedSubtitleFile> = emptyList()
) {
    val matchedCount: Int get() = files.count { it.isMatched }
}

@Immutable
@Serializable
data class ImportedSubtitleFile(
    val fileName: String,
    /** Path below the imported-subtitles root, so the pack survives a reinstall path change. */
    val relativePath: String,
    /** Episode number as the file name spells it, before any placement. */
    val parsedEpisode: Int? = null,
    /** Season the file name states, if it states one. */
    val parsedSeason: Int? = null,
    /** The metadata addon's own video id — the exact key the player asks with. */
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null
) {
    val isMatched: Boolean get() = videoId != null
}

/** A stored subtitle that answers for the episode being played. */
@Immutable
data class ImportedSubtitleMatch(
    val pack: ImportedSubtitlePack,
    val file: ImportedSubtitleFile
)

/** Everything is imported as Russian: it is the only language this is used for. */
const val IMPORTED_SUBTITLE_LANGUAGE = "rus"

val SUBTITLE_FILE_EXTENSIONS = setOf(
    "ass", "ssa", "srt", "vtt", "sub", "sbv", "smi", "ttml", "dfxp"
)

fun String.isSubtitleFileName(): Boolean =
    substringAfterLast('.', missingDelimiterValue = "").lowercase() in SUBTITLE_FILE_EXTENSIONS
