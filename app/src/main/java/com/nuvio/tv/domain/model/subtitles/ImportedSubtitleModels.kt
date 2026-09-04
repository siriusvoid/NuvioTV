package com.nuvio.tv.domain.model.subtitles

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A folder of subtitle files the user pointed Nuvio at.
 *
 * Everything below it is scanned and matched, the way a WebDAV share or a local
 * media folder is. The files are never copied or moved: removing a source drops
 * the index and nothing else.
 */
@Immutable
@Serializable
data class SubtitleFolderSource(
    val id: String,
    /** Absolute path of the folder the user picked. */
    val path: String,
    val displayName: String,
    val enabled: Boolean = true,
    val lastScanAt: Long? = null
)

/**
 * One release inside a source: the subtitle files that share a title, placed on
 * a show's episodes.
 *
 * The title comes from the file names rather than the folder name. A subtitle
 * folder is usually named after the fansub group — "mudabone", "AniLibria" —
 * which no anime database has ever heard of, while every file inside spells the
 * show out.
 */
@Immutable
@Serializable
data class ImportedSubtitlePack(
    val id: String,
    val sourceId: String,
    /** Directory holding the files, absolute. */
    val folderPath: String,
    /** Title read out of the file names, kept for the settings rows. */
    val releaseTitle: String,
    /** Id in whatever space the metadata addon serves, e.g. tt0972656. */
    val metaId: String,
    val metaType: String,
    val showName: String,
    val language: String = IMPORTED_SUBTITLE_LANGUAGE,
    val confidence: Float = 0f,
    val files: List<ImportedSubtitleFile> = emptyList()
) {
    val matchedCount: Int get() = files.count { it.isMatched }
}

@Immutable
@Serializable
data class ImportedSubtitleFile(
    val fileName: String,
    /** Absolute path. The file stays where the user put it. */
    val path: String,
    /** The metadata addon's own video id — the exact key the player asks with. */
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null
) {
    val isMatched: Boolean get() = videoId != null
}

/** A release the scanner could not place, listed so it is not silently lost. */
@Immutable
@Serializable
data class UnmatchedSubtitleFolder(
    val sourceId: String,
    val folderPath: String,
    val releaseTitle: String,
    val fileCount: Int
)

/** A stored subtitle that answers for the episode being played. */
@Immutable
data class ImportedSubtitleMatch(
    val pack: ImportedSubtitlePack,
    val file: ImportedSubtitleFile
)

/** How a source's scan is getting on, for the settings rows. */
@Immutable
sealed interface SubtitleScanProgress {
    data class Scanning(val foldersFound: Int) : SubtitleScanProgress
    data class Matching(val done: Int, val total: Int) : SubtitleScanProgress
    data class Failed(val reason: String) : SubtitleScanProgress
    data object Idle : SubtitleScanProgress
}

/** Everything is imported as Russian: it is the only language this is used for. */
const val IMPORTED_SUBTITLE_LANGUAGE = "rus"

val SUBTITLE_FILE_EXTENSIONS = setOf(
    "ass", "ssa", "srt", "vtt", "sub", "sbv", "smi", "ttml", "dfxp"
)

fun String.isSubtitleFileName(): Boolean =
    substringAfterLast('.', missingDelimiterValue = "").lowercase() in SUBTITLE_FILE_EXTENSIONS
