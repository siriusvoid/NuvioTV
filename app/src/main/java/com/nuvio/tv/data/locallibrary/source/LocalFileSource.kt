package com.nuvio.tv.data.locallibrary.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import com.nuvio.tv.data.locallibrary.match.FilenameParser
import com.nuvio.tv.data.locallibrary.subtitle.SubtitleFilenameParser
import com.nuvio.tv.domain.model.locallibrary.ExternalSubtitleFile
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.ResolvedStream
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * On-device media source backed by one of two storage strategies, chosen from
 * [LocalLibrarySourceConfig.urlOrPath]:
 *
 *  - **SAF** (`content://…` tree URI from `ACTION_OPEN_DOCUMENT_TREE`) — used on
 *    devices that ship a system document picker. Persisted permission must have
 *    been taken via `takePersistableUriPermission` during the Add Source flow.
 *  - **Direct path** (`file://…` or an absolute path) — used on Android TV /
 *    Google TV, which has no SAF picker. The folder is chosen via the in-app
 *    folder browser, and the `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE`
 *    runtime permission must already have been granted.
 */
class LocalFileSource(
    override val config: LocalLibrarySourceConfig,
    private val context: Context
) : LocalLibrarySource {

    private val isSaf: Boolean = config.urlOrPath.startsWith("content://")
    private val rootUri: Uri = Uri.parse(config.urlOrPath)
    private val rootFile: File? = if (isSaf) null else resolveRootFile(config.urlOrPath)

    override fun scan(): Flow<ScannedItem> = if (isSaf) scanSaf() else scanFile()

    override suspend fun resolveStream(item: ScannedItem): ResolvedStream? {
        val url = item.directStreamUrl ?: return null
        val subtitles = withContext(Dispatchers.IO) { discoverSidecarSubtitles(item) }
        return ResolvedStream(
            url = url,
            scheme = if (isSaf) "content" else "file",
            sizeBytes = item.sizeBytes,
            subtitles = subtitles
        )
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        if (isSaf) {
            val root = DocumentFile.fromTreeUri(context, rootUri)
                ?: error("Tree URI not accessible — permissions may have been revoked")
            require(root.isDirectory) { "Tree URI is not a directory: $rootUri" }
        } else {
            val root = rootFile ?: error("Invalid folder path: ${config.urlOrPath}")
            require(root.isDirectory) { "Not a directory: ${root.absolutePath}" }
            require(root.canRead()) { "Folder is not readable: ${root.absolutePath}" }
        }
    }

    private fun discoverSidecarSubtitles(item: ScannedItem): List<ExternalSubtitleFile> =
        if (isSaf) discoverSidecarSubtitlesSaf(item) else discoverSidecarSubtitlesFile(item)

    // ─────────────────────────────────────────────────────────────────────────
    // Direct filesystem backend (Android TV / Google TV)
    // ─────────────────────────────────────────────────────────────────────────

    private fun scanFile(): Flow<ScannedItem> = flow {
        val root = rootFile ?: error("Invalid folder path: ${config.urlOrPath}")
        for (file in traverseFile(root)) {
            val rel = file.absolutePath.removePrefix(root.absolutePath).trimStart('/')
            val parsed = FilenameParser.parse(file.name)
            emit(
                ScannedItem(
                    sourceId = config.id,
                    relativePath = rel,
                    fileName = file.name,
                    sizeBytes = file.length().takeIf { it > 0 },
                    parsedTitle = parsed.title,
                    parsedYear = parsed.year,
                    parsedSeason = parsed.season,
                    parsedEpisode = parsed.episode,
                    typeHint = parsed.contentType,
                    directStreamUrl = Uri.fromFile(file).toString()
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private fun discoverSidecarSubtitlesFile(item: ScannedItem): List<ExternalSubtitleFile> {
        return try {
            val root = rootFile ?: return emptyList()
            val parentRel = item.relativePath.replace('\\', '/').trim('/')
                .substringBeforeLast('/', missingDelimiterValue = "")
            val parent = if (parentRel.isEmpty()) root else File(root, parentRel)
            if (!parent.isDirectory) return emptyList()
            val videoBase = item.fileName.substringBeforeLast('.')
            if (videoBase.isBlank()) return emptyList()
            (parent.listFiles() ?: emptyArray()).mapNotNull { child ->
                if (child.isDirectory) return@mapNotNull null
                val name = child.name
                if (!SubtitleFilenameParser.matchesVideo(name, videoBase)) return@mapNotNull null
                val ext = name.substringAfterLast('.', missingDelimiterValue = "")
                val parsed = SubtitleFilenameParser.parse(name, videoBase)
                ExternalSubtitleFile(
                    url = Uri.fromFile(child).toString(),
                    displayName = parsed.displayName,
                    language = parsed.language,
                    mimeType = SubtitleFilenameParser.mimeTypeFor(ext),
                    isForced = parsed.isForced,
                    source = ExternalSubtitleFile.Source.LOCAL_SIDECAR
                )
            }.also {
                if (it.isNotEmpty()) Log.i(TAG, "discovered ${it.size} sidecar subtitle(s) for ${item.fileName}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sidecar discovery failed for ${item.fileName}", t)
            emptyList()
        }
    }

    private fun traverseFile(root: File): List<File> {
        val results = mutableListOf<File>()
        // Pair each directory with its depth to guard against symlink cycles.
        val stack = ArrayDeque<Pair<File, Int>>().apply { addLast(root to 0) }
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) continue
            val children = try {
                dir.listFiles()
            } catch (_: Throwable) {
                null
            } ?: continue
            for (child in children) {
                when {
                    child.isDirectory -> stack.addLast(child to depth + 1)
                    isVideoFile(child.name) -> results += child
                }
            }
        }
        return results
    }

    private fun resolveRootFile(value: String): File? = try {
        if (value.startsWith("file://")) {
            Uri.parse(value).path?.let { File(it) }
        } else {
            File(value)
        }
    } catch (_: Throwable) {
        null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SAF backend (devices with a system document picker)
    // ─────────────────────────────────────────────────────────────────────────

    private fun scanSaf(): Flow<ScannedItem> = flow {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: error("Cannot open tree URI: $rootUri")
        traverseSaf(root, "").forEach { (relPath, docFile) ->
            val parsed = FilenameParser.parse(docFile.name.orEmpty())
            emit(
                ScannedItem(
                    sourceId = config.id,
                    relativePath = relPath,
                    fileName = docFile.name.orEmpty(),
                    sizeBytes = docFile.length().takeIf { it > 0 },
                    parsedTitle = parsed.title,
                    parsedYear = parsed.year,
                    parsedSeason = parsed.season,
                    parsedEpisode = parsed.episode,
                    typeHint = parsed.contentType,
                    directStreamUrl = docFile.uri.toString()
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private fun discoverSidecarSubtitlesSaf(item: ScannedItem): List<ExternalSubtitleFile> {
        return try {
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
            val parent = walkToParent(root, item.relativePath) ?: return emptyList()
            val videoBase = item.fileName.substringBeforeLast('.')
            if (videoBase.isBlank()) return emptyList()
            parent.listFiles().mapNotNull { child ->
                val name = child.name ?: return@mapNotNull null
                if (child.isDirectory) return@mapNotNull null
                if (!SubtitleFilenameParser.matchesVideo(name, videoBase)) return@mapNotNull null
                val ext = name.substringAfterLast('.', missingDelimiterValue = "")
                val parsed = SubtitleFilenameParser.parse(name, videoBase)
                ExternalSubtitleFile(
                    url = child.uri.toString(),
                    displayName = parsed.displayName,
                    language = parsed.language,
                    mimeType = SubtitleFilenameParser.mimeTypeFor(ext),
                    isForced = parsed.isForced,
                    source = ExternalSubtitleFile.Source.LOCAL_SIDECAR
                )
            }.also {
                if (it.isNotEmpty()) Log.i(TAG, "discovered ${it.size} sidecar subtitle(s) for ${item.fileName}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sidecar discovery failed for ${item.fileName}", t)
            emptyList()
        }
    }

    private fun walkToParent(root: DocumentFile, relativePath: String): DocumentFile? {
        val normalized = relativePath.trim('/').replace('\\', '/')
        val parentPath = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentPath.isEmpty()) return root
        var current: DocumentFile? = root
        for (segment in parentPath.split('/')) {
            if (segment.isBlank()) continue
            current = current?.findFile(segment) ?: return null
            if (current?.isDirectory != true) return null
        }
        return current
    }

    private fun traverseSaf(
        root: DocumentFile,
        prefix: String
    ): List<Pair<String, DocumentFile>> {
        val results = mutableListOf<Pair<String, DocumentFile>>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>().apply { addLast(root to prefix) }
        while (stack.isNotEmpty()) {
            val (dir, dirPath) = stack.removeLast()
            val children = try {
                dir.listFiles()
            } catch (_: Throwable) {
                continue
            }
            for (child in children) {
                val name = child.name ?: continue
                val rel = if (dirPath.isEmpty()) name else "$dirPath/$name"
                when {
                    child.isDirectory -> stack.addLast(child to rel)
                    isVideoFile(name) -> results += rel to child
                }
            }
        }
        return results
    }

    private fun isVideoFile(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return false
        return name.substring(dot + 1).lowercase() in VIDEO_EXTS
    }

    companion object {
        private const val TAG = "LocalFileSource"
        private const val MAX_DEPTH = 12
        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg", "m4v"
        )
    }
}
