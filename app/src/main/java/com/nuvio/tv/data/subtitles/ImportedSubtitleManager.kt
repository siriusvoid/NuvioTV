package com.nuvio.tv.data.subtitles

import android.net.Uri
import android.util.Log
import com.nuvio.tv.data.local.ImportedSubtitlePreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.webdav.AnimeReleaseParser
import com.nuvio.tv.data.webdav.AnimeSearchClient
import com.nuvio.tv.data.webdav.ArmMappingClient
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitleFile
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitleMatch
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import com.nuvio.tv.domain.model.subtitles.isSubtitleFileName
import com.nuvio.tv.domain.repository.ImportedSubtitleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imported subtitles: copied in, matched to the show that started the import, and
 * dropped once it's watched through unless kept.
 */
@Singleton
internal class ImportedSubtitleManager @Inject constructor(
    private val preferences: ImportedSubtitlePreferences,
    private val storage: ImportedSubtitleStorage,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val animeSearch: AnimeSearchClient,
    private val armMapping: ArmMappingClient
) : ImportedSubtitleGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _packs = MutableStateFlow<List<ImportedSubtitlePack>>(emptyList())
    override val packs: StateFlow<List<ImportedSubtitlePack>> = _packs.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    init {
        scope.launch {
            preferences.packs.collect { stored ->
                _packs.value = stored
                _loaded.value = true
            }
        }
        scope.launch {
            combine(preferences.packs, watchedItemsPreferences.allItems) { packs, watched ->
                packs to watched
            }.collect { (packs, watched) -> removeWatchedPacks(packs, watched) }
        }
    }

    override fun packsFor(metaId: String): List<ImportedSubtitlePack> =
        _packs.value.filter { it.metaId == metaId }

    override suspend fun import(meta: Meta, folder: File): Int {
        awaitLoaded()
        val subtitleFiles = subtitleFilesIn(folder)
        if (subtitleFiles.isEmpty()) return 0

        val packId = UUID.randomUUID().toString()
        val adopted = subtitleFiles.mapNotNull { file ->
            val relativePath = storage.adopt(
                source = file,
                packId = packId,
                fileName = file.name
            ) ?: return@mapNotNull null
            ImportedSubtitleMatcher.parse(file.name).copy(relativePath = relativePath)
        }
        if (adopted.isEmpty()) {
            storage.deletePack(packId)
            return 0
        }

        val sourceName = folder.name.takeIf { it.isNotBlank() }
        // Offline or unknown titles leave the season to the placement ladder instead of blocking.
        val mapperSeason = withTimeoutOrNull(SEASON_LOOKUP_TIMEOUT_MS) {
            databaseSeason(ImportedSubtitleMatcher.releaseTitle(adopted.map { it.fileName }))
        }
        val pack = ImportedSubtitlePack(
            id = packId,
            metaId = meta.id,
            metaType = meta.apiType,
            showName = meta.name,
            importedAt = System.currentTimeMillis(),
            sourceName = sourceName,
            mapperSeason = mapperSeason,
            files = adopted
        ).placed(meta)

        // Re-importing a folder replaces it; groups share file names, so the folder must match too.
        val replacedNames = pack.files.mapTo(mutableSetOf()) { it.fileName }
        val remaining = _packs.value.mapNotNull { existing ->
            if (existing.metaId != meta.id || existing.sourceName != pack.sourceName) {
                return@mapNotNull existing
            }
            val (superseded, kept) = existing.files.partition { it.fileName in replacedNames }
            superseded.forEach { storage.deleteFile(it.relativePath) }
            if (kept.isEmpty()) {
                storage.deletePack(existing.id)
                null
            } else {
                existing.copy(files = kept)
            }
        }

        publish(remaining + pack)
        Log.i(TAG, "Imported ${pack.files.size} subtitles for ${meta.name}, ${pack.matchedCount} matched")
        return pack.files.size
    }

    override suspend fun setKeepAfterWatching(packId: String, keep: Boolean) {
        publish(
            _packs.value.map { pack ->
                if (pack.id == packId) pack.copy(keepAfterWatching = keep) else pack
            }
        )
    }

    override suspend fun deletePack(packId: String) {
        storage.deletePack(packId)
        publish(_packs.value.filterNot { it.id == packId })
    }

    /** Matches by video id, or by season/episode when the show's id space moved under the pack. */
    override suspend fun subtitlesFor(
        videoId: String,
        metaId: String?,
        season: Int?,
        episode: Int?
    ): List<ImportedSubtitleMatch> {
        awaitLoaded()
        return _packs.value.flatMap { pack ->
            pack.files
                .filter { file ->
                    when {
                        file.videoId == videoId -> true
                        metaId == null || pack.metaId != metaId -> false
                        season == null || episode == null -> false
                        else -> file.season == season && file.episode == episode
                    }
                }
                .filter { storage.exists(it.relativePath) }
                .map { file -> ImportedSubtitleMatch(pack = pack, file = file) }
        }
    }

    override fun subtitleUrl(relativePath: String): String = storage.subtitleUrl(relativePath)

    /** Matching against the index first keeps this from reading anything else. */
    override fun readSubtitleText(url: String): String? = storedFile(url)?.let { (pack, file) ->
        storage.readText(file.relativePath, languageHint = pack.language)
    }

    /** The pack and file a url names; only the last two segments count, so older installs still resolve. */
    private fun storedFile(url: String): Pair<ImportedSubtitlePack, ImportedSubtitleFile>? {
        if (!url.startsWith("file:", ignoreCase = true) && !url.startsWith("/")) return null
        val path = runCatching { Uri.parse(url).path }.getOrNull() ?: url
        val fileName = path.substringAfterLast('/')
        val packId = path.removeSuffix("/$fileName").substringAfterLast('/')
        if (fileName.isBlank() || packId.isBlank()) return null
        val relativePath = "$packId/$fileName"
        return _packs.value.firstNotNullOfOrNull { pack ->
            pack.files.firstOrNull { it.relativePath == relativePath }?.let { pack to it }
        }
    }

    /** Subtitle files sitting directly in [folder], in the order the pack reads. */
    private fun subtitleFilesIn(folder: File): List<File> =
        (folder.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.isSubtitleFileName() }
            .sortedBy { it.name.lowercase() }

    private suspend fun awaitLoaded() {
        _loaded.first { it }
    }

    private fun ImportedSubtitlePack.placed(meta: Meta?): ImportedSubtitlePack =
        copy(
            files = ImportedSubtitleMatcher.place(
                files = files,
                meta = meta,
                seasonHint = ImportedSubtitleMatcher.seasonHint(sourceName) ?: mapperSeason,
                isMovie = metaType.equals("movie", ignoreCase = true),
                metaId = metaId
            )
        )

    /**
     * The database season of a cour-titled release ("Hidamari Sketch x Honeycomb" is season 4).
     * Without it the numbers read as absolute and the whole pack lands on season 1.
     */
    private suspend fun databaseSeason(title: String?): Int? {
        val query = title?.takeIf { it.isNotBlank() } ?: return null
        val hits = runCatching { animeSearch.search(query) }.getOrNull().orEmpty()
        val best = hits
            .map { hit ->
                hit to (hit.allTitles.maxOfOrNull { AnimeReleaseParser.similarity(query, it) } ?: 0f)
            }
            .maxByOrNull { it.second }
            ?: return null
        if (best.second < MIN_TITLE_SIMILARITY) return null
        return runCatching { armMapping.lookup(best.first.source, best.first.id)?.season }.getOrNull()
    }

    /** Drops a pack once all its episodes are watched; unmatched packs stay, there's nothing to compare. */
    private suspend fun removeWatchedPacks(
        packs: List<ImportedSubtitlePack>,
        watched: List<WatchedItem>
    ) {
        if (packs.isEmpty() || watched.isEmpty()) return
        val watchedKeys = watched.mapTo(mutableSetOf()) { item ->
            watchedKey(item.contentId, item.season, item.episode)
        }
        val expired = packs.filter { pack ->
            if (pack.keepAfterWatching) return@filter false
            val matched = pack.files.filter { it.isMatched }
            if (matched.isEmpty() || matched.size != pack.files.size) return@filter false
            matched.all { file -> watchedKey(pack.metaId, file.season, file.episode) in watchedKeys }
        }
        if (expired.isEmpty()) return

        expired.forEach { pack ->
            storage.deletePack(pack.id)
            Log.i(TAG, "Removed watched subtitle pack for ${pack.showName}")
        }
        val expiredIds = expired.mapTo(mutableSetOf()) { it.id }
        publish(packs.filterNot { it.id in expiredIds })
    }

    private fun watchedKey(contentId: String, season: Int?, episode: Int?): String =
        "$contentId|${season ?: -1}|${episode ?: -1}"

    private suspend fun publish(packs: List<ImportedSubtitlePack>) {
        val ordered = packs.sortedWith(
            compareBy({ it.showName.lowercase() }, { it.importedAt })
        )
        _packs.value = ordered
        runCatching { preferences.save(ordered) }
            .onFailure { error -> Log.w(TAG, "Could not save the imported subtitle index", error) }
    }

    companion object {
        private const val TAG = "ImportedSubtitles"

        /** Below this the search hit is a different show, and its season would mislead. */
        private const val MIN_TITLE_SIMILARITY = 0.7f

        /** Long enough for two lookups on a slow connection, short enough not to stall. */
        private const val SEASON_LOOKUP_TIMEOUT_MS = 8_000L
    }
}
