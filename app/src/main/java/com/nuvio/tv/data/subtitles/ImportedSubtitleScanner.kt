package com.nuvio.tv.data.subtitles

import android.util.Log
import com.nuvio.tv.data.matching.ReleaseMatch
import com.nuvio.tv.data.matching.ReleaseMatcher
import com.nuvio.tv.data.webdav.AnimeReleaseParser
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.webdav.ParsedRelease
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitleFile
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import com.nuvio.tv.domain.model.subtitles.SubtitleFolderSource
import com.nuvio.tv.domain.model.subtitles.SubtitleScanProgress
import com.nuvio.tv.domain.model.subtitles.UnmatchedSubtitleFolder
import com.nuvio.tv.domain.model.subtitles.isSubtitleFileName
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a folder of subtitle files into placed episodes.
 *
 * The tree is walked for directories that hold subtitle files directly, and the
 * files in each are grouped by the title their names spell out — so one folder
 * holding two shows becomes two releases, and it makes no difference whether the
 * user pointed at "Anime Series" or at the "Subs" folder inside it.
 */
@Singleton
internal class ImportedSubtitleScanner @Inject constructor(
    private val releaseMatcher: ReleaseMatcher
) {

    data class ScanResult(
        val packs: List<ImportedSubtitlePack>,
        val unmatched: List<UnmatchedSubtitleFolder>
    )

    /** One directory's worth of files that agree on a title. */
    private data class ReleaseGroup(
        val folder: File,
        val title: String,
        val files: List<File>
    )

    suspend fun scan(
        source: SubtitleFolderSource,
        onProgress: (SubtitleScanProgress) -> Unit
    ): ScanResult {
        val root = File(source.path)
        if (!root.isDirectory) {
            return ScanResult(emptyList(), emptyList())
        }

        val groups = collectGroups(root) { found -> onProgress(SubtitleScanProgress.Scanning(found)) }
        val packs = mutableListOf<ImportedSubtitlePack>()
        val unmatched = mutableListOf<UnmatchedSubtitleFolder>()

        groups.forEachIndexed { index, group ->
            onProgress(SubtitleScanProgress.Matching(done = index, total = groups.size))
            val pack = runCatching { resolve(source, group) }
                .getOrElse { error ->
                    Log.w(TAG, "Could not match ${group.title} in ${group.folder.path}", error)
                    null
                }
            if (pack != null) {
                packs += pack
            } else {
                unmatched += UnmatchedSubtitleFolder(
                    sourceId = source.id,
                    folderPath = group.folder.absolutePath,
                    releaseTitle = group.title,
                    fileCount = group.files.size
                )
            }
        }
        onProgress(SubtitleScanProgress.Matching(done = groups.size, total = groups.size))
        Log.i(
            TAG,
            "Scanned ${source.path}: ${groups.size} release(s), ${packs.size} matched, " +
                "${packs.sumOf { it.files.size }} file(s)"
        )
        return ScanResult(packs = packs, unmatched = unmatched)
    }

    private suspend fun resolve(source: SubtitleFolderSource, group: ReleaseGroup): ImportedSubtitlePack? {
        val fileNames = group.files.map { it.name }
        val parsed = releaseFor(group, fileNames)
        val match = releaseMatcher.match(parsed, fileNames) ?: return null
        val isMovie = match.contentType == ReleaseMatch.CONTENT_TYPE_MOVIE

        val files = group.files.map { file ->
            place(file = file, match = match, isMovie = isMovie)
        }
        if (files.none { it.isMatched }) return null

        return ImportedSubtitlePack(
            id = "${source.id}|${group.folder.absolutePath}|${group.title}",
            sourceId = source.id,
            folderPath = group.folder.absolutePath,
            releaseTitle = group.title,
            metaId = match.contentId,
            metaType = match.contentType,
            showName = match.meta?.name ?: match.title,
            confidence = match.confidence,
            files = files
        )
    }

    /**
     * The release as the matcher wants it: the title the files agree on, plus any
     * season one of them states. The folder name is only consulted for a season,
     * since a group name carries none of the rest.
     */
    private fun releaseFor(group: ReleaseGroup, fileNames: List<String>): ParsedRelease {
        val statedSeason = fileNames
            .firstNotNullOfOrNull { AnimeReleaseParser.parseFile(it).season?.takeIf { season -> season > 0 } }
            ?: AnimeReleaseParser.parseFolder(group.folder.name).season
        val episodes = fileNames.mapNotNull { AnimeReleaseParser.parseFile(it).episode }
        return ParsedRelease(
            title = group.title,
            season = statedSeason,
            episode = episodes.minOrNull(),
            episodeRange = episodes.minOrNull()?.let { first -> episodes.maxOrNull()?.let { first..it } }
        )
    }

    /**
     * Season and episode come from the match, not from a second placement run: the
     * matcher already worked out which season this release is and how far its
     * numbering is offset, so every file only has to follow the same shift.
     */
    private fun place(file: File, match: ReleaseMatch, isMovie: Boolean): ImportedSubtitleFile {
        if (isMovie) {
            return ImportedSubtitleFile(
                fileName = file.name,
                path = file.absolutePath,
                videoId = match.contentId
            )
        }

        val parsed = AnimeReleaseParser.parseFile(file.name)
        val episodeNumber = parsed.episode
        // A special stays a special: it is numbered among season 0 rather than
        // shifted onto the run the rest of the folder belongs to.
        val season = if (parsed.season == 0) 0 else match.season
        val episode = if (parsed.season == 0) episodeNumber else episodeNumber?.plus(match.episodeOffset)

        return ImportedSubtitleFile(
            fileName = file.name,
            path = file.absolutePath,
            videoId = videoIdFor(match.meta, season, episode),
            season = season,
            episode = episode
        )
    }

    private fun videoIdFor(meta: Meta?, season: Int?, episode: Int?): String? {
        if (meta == null || season == null || episode == null) return null
        return meta.videos.firstOrNull { it.season == season && it.episode == episode }?.id
    }

    /**
     * Every directory below [root] that holds subtitle files, split into releases.
     *
     * Walked iteratively with a depth cap and a set of canonical paths already
     * seen, so a symlink loop cannot spin the scan.
     */
    private fun collectGroups(root: File, onFound: (Int) -> Unit): List<ReleaseGroup> {
        val groups = mutableListOf<ReleaseGroup>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<File, Int>>().apply { addLast(root to 0) }

        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val canonical = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
            if (!seen.add(canonical)) continue

            val children = dir.listFiles() ?: continue
            if (depth < MAX_DEPTH) {
                children.filter { it.isDirectory && !it.isHidden }
                    .sortedBy { it.name.lowercase() }
                    .forEach { queue.addLast(it to depth + 1) }
            }

            val subtitles = children
                .filter { it.isFile && it.name.isSubtitleFileName() }
                .sortedBy { it.name.lowercase() }
            if (subtitles.isEmpty()) continue

            groups += groupByTitle(dir, root, subtitles)
            onFound(groups.size)
        }
        return groups
    }

    /**
     * Splits one directory's files by the title their names carry. Files whose
     * names say nothing fall back to the folder they are in — skipping names like
     * "Subs" that describe the contents rather than the show.
     */
    private fun groupByTitle(dir: File, root: File, subtitles: List<File>): List<ReleaseGroup> {
        val fallback = fallbackTitle(dir, root)
        return subtitles
            .groupBy { file ->
                AnimeReleaseParser.parseFile(file.name).title
                    .takeIf { it.isNotBlank() }
                    ?: fallback
            }
            .filterKeys { it.isNotBlank() }
            .map { (title, files) -> ReleaseGroup(folder = dir, title = title, files = files) }
    }

    /** The nearest folder name that describes a show rather than its contents. */
    private fun fallbackTitle(dir: File, root: File): String {
        var current: File? = dir
        while (current != null) {
            val parsed = AnimeReleaseParser.parseFolder(current.name).title
            if (parsed.isNotBlank() && !isGenericFolderName(current.name)) return parsed
            if (current.absolutePath == root.absolutePath) break
            current = current.parentFile
        }
        return ""
    }

    private fun isGenericFolderName(name: String): Boolean =
        name.trim().lowercase() in GENERIC_FOLDER_NAMES

    private companion object {
        const val TAG = "SubtitleScanner"

        /** Deep enough for "Anime Series / Show / Subs", shallow enough not to crawl a drive. */
        const val MAX_DEPTH = 4

        /** Folder names that say what is inside rather than which show it is. */
        val GENERIC_FOLDER_NAMES = setOf(
            "subs", "sub", "subtitles", "subtitle", "russian", "rus", "english", "eng",
            "signs", "songs", "full", "dialogue"
        )
    }
}
