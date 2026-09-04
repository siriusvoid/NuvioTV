package com.nuvio.tv.data.webdav

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.locallibrary.subtitle.SubtitleFilenameParser
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ExternalSubtitle
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.webdav.WebDavFile
import com.nuvio.tv.domain.model.webdav.WebDavFolder
import com.nuvio.tv.domain.model.webdav.WebDavMatch
import com.nuvio.tv.domain.model.webdav.WebDavSource
import com.nuvio.tv.domain.repository.WebDavGateway
import com.nuvio.tv.domain.repository.WebDavGateway.Companion.ADDON_ID
import com.nuvio.tv.domain.repository.WebDavGateway.Companion.ADDON_NAME
import com.nuvio.tv.domain.repository.WebDavGateway.Companion.SYNTHETIC_BASE_URL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class WebDavGatewayImpl @Inject constructor(
    private val manager: WebDavManager,
    private val index: WebDavIndex
) : WebDavGateway {

    override fun synthesizeAddon(): Flow<Addon?> =
        combine(manager.sources, manager.catalogRevision) { sources, revision ->
            val enabled = sources.filter { it.enabled }
            if (enabled.isEmpty()) null else buildAddon(enabled, revision)
        }

    override fun isWebDavAddon(addonId: String?, baseUrl: String?): Boolean =
        addonId == ADDON_ID || baseUrl?.startsWith(SYNTHETIC_BASE_URL) == true

    override fun scanOnLaunch() = manager.scanOnLaunch()

    override suspend fun catalog(
        catalogId: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>
    ): NetworkResult<CatalogRow> {
        val parsed = WebDavCatalogId.parse(catalogId)
            ?: return NetworkResult.Error("Unknown WebDAV catalog: $catalogId")
        val source = manager.sources.value.firstOrNull { it.id == parsed.sourceId }
            ?: return NetworkResult.Error("WebDAV source ${parsed.sourceId} is not configured")

        val pageSize = skipStep.takeIf { it > 0 } ?: DEFAULT_PAGE_SIZE
        // Matching against the display name as well as the database title: the row
        // shows whichever the metadata addon serves, and a query typed off the screen
        // has to find the item the user is looking at.
        val query = extraArgs["search"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val entries = index.catalogEntries(
            sourceId = parsed.sourceId,
            contentType = parsed.type.toApiString()
        ).let { matches ->
            if (query == null) {
                matches
            } else {
                matches.filter {
                    it.displayName.lowercase().contains(query) ||
                        it.title.lowercase().contains(query)
                }
            }
        }
        val page = entries.drop(skip).take(pageSize)

        return NetworkResult.Success(
            CatalogRow(
                addonId = ADDON_ID,
                addonName = ADDON_NAME,
                addonBaseUrl = SYNTHETIC_BASE_URL,
                catalogId = catalogId,
                catalogName = catalogName(source, parsed.type),
                type = parsed.type,
                items = page.map { it.toPreview(parsed.type) },
                hasMore = entries.size > skip + page.size,
                currentPage = skip / pageSize,
                supportsSkip = true,
                skipStep = pageSize,
                extraArgs = extraArgs
            )
        )
    }

    override suspend fun streams(type: String, videoId: String): NetworkResult<List<Stream>> {
        val parts = parseVideoId(videoId)
        val candidates = index.foldersForContentId(parts.contentId)
        if (candidates.isEmpty()) return NetworkResult.Success(emptyList())

        // The credential lookup reads storage and does not vary by file, so it is
        // resolved once per source rather than once per stream.
        val headersBySource = HashMap<String, Map<String, String>>()
        val sources = manager.sources.value.associateBy { it.id }

        val streams = ArrayList<Stream>()
        candidates.forEach { (folder, match) ->
            val headers = headersBySource.getOrPut(match.sourceId) {
                runCatching { manager.playbackHeaders(match.sourceId) }
                    .onFailure { Log.w(TAG, "Could not build headers for ${match.sourceId}", it) }
                    .getOrDefault(emptyMap())
            }
            val sourceName = sources[match.sourceId]?.displayName ?: ADDON_NAME
            selectFiles(folder, match, parts).forEach { file ->
                streams += buildStream(file, folder, match, headers, sourceName)
            }
        }
        return NetworkResult.Success(streams)
    }

    private fun buildAddon(enabled: List<WebDavSource>, revision: Int): Addon {
        val catalogs = enabled.flatMap { source ->
            listOf(ContentType.MOVIE, ContentType.SERIES).map { type ->
                CatalogDescriptor(
                    type = type,
                    id = WebDavCatalogId.format(source.id, type),
                    name = catalogName(source, type),
                    extra = listOf(CatalogExtra("skip"), CatalogExtra("search")),
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            }
        }
        return Addon(
            id = ADDON_ID,
            name = ADDON_NAME,
            displayName = ADDON_NAME,
            // The revision rides along so a scan that changes the index also changes
            // the addon, which is how the home rows learn they have to refetch.
            version = "1.0.$revision",
            description = "Your debrid WebDAV library, mapped to your metadata addon",
            logo = null,
            baseUrl = SYNTHETIC_BASE_URL,
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(
                AddonResource("catalog", listOf("movie", "series"), null),
                // No idPrefixes: matched items keep the metadata addon's ids, so the
                // app has to ask about every title the user opens.
                AddonResource("stream", listOf("movie", "series"), null)
            ),
            idPrefixes = emptyList()
        )
    }

    /**
     * A source contributes one row per type, so the rows carry the type as well as
     * the source — two rows titled the same would otherwise sit next to each other
     * on the home screen.
     */
    private fun catalogName(source: WebDavSource, type: ContentType): String =
        "${source.displayName} · ${if (type == ContentType.MOVIE) "Movies" else "Series"}"

    private fun WebDavMatch.toPreview(type: ContentType): MetaPreview = MetaPreview(
        id = contentId,
        type = type,
        name = displayName,
        poster = displayPoster,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun selectFiles(
        folder: WebDavFolder,
        match: WebDavMatch,
        parts: VideoIdParts
    ): List<WebDavFile> {
        if (match.contentType == WebDavMatch.CONTENT_TYPE_MOVIE) {
            return folder.files.sortedByDescending { it.sizeBytes ?: 0L }.take(MOVIE_FILE_LIMIT)
        }

        val requestedEpisode = parts.episode ?: return emptyList()
        val requestedSeason = parts.season ?: match.season

        return folder.files.filter { file ->
            val parsed = AnimeReleaseParser.parseFile(file.fileName)
            val parsedEpisode = parsed.episode ?: return@filter false

            // A file that names its own season — specials as season 0 — is placed by it;
            // otherwise the folder's season stands in. Without this a special would also
            // answer for the numbered episode with the same number.
            val fileSeason = parsed.season ?: match.season
            if (requestedSeason != null && fileSeason != null && fileSeason != requestedSeason) {
                return@filter false
            }

            // The folder's offset applies to its numbered run, not to specials.
            val offset = if (parsed.season == 0) 0 else match.episodeOffset
            parsedEpisode + offset == requestedEpisode
        }
    }

    private fun buildStream(
        file: WebDavFile,
        folder: WebDavFolder,
        match: WebDavMatch,
        headers: Map<String, String>,
        sourceName: String
    ): Stream = Stream(
        name = sourceName,
        title = file.fileName,
        description = "${folder.name}\n${file.fileName}",
        url = file.url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = true,
            bingeGroup = "webdav-${match.sourceId}-${match.contentId}",
            countryWhitelist = null,
            proxyHeaders = headers.takeIf { it.isNotEmpty() }
                ?.let { ProxyHeaders(request = it, response = null) },
            videoSize = file.sizeBytes,
            filename = file.fileName
        ),
        addonName = ADDON_NAME,
        addonLogo = null,
        externalSubtitles = sidecarSubtitles(folder, file, headers)
    )

    /**
     * Subtitle files sitting beside the video in the same torrent folder. A folder
     * holding a single video keeps all of them, since a season pack's naming is the
     * only thing that makes per-file matching necessary.
     */
    private fun sidecarSubtitles(
        folder: WebDavFolder,
        file: WebDavFile,
        headers: Map<String, String>
    ): List<ExternalSubtitle> {
        if (folder.subtitles.isEmpty()) return emptyList()
        val videoBaseName = file.fileName.substringBeforeLast('.')
        val candidates = if (folder.files.size <= 1) {
            folder.subtitles
        } else {
            folder.subtitles.filter {
                SubtitleFilenameParser.matchesVideo(it.fileName, videoBaseName)
            }
        }
        return candidates.map { subtitle ->
            val info = SubtitleFilenameParser.parse(subtitle.fileName, videoBaseName)
            ExternalSubtitle(
                url = subtitle.url,
                displayName = info.displayName,
                language = info.language,
                mimeType = SubtitleFilenameParser.mimeTypeFor(subtitle.fileName.fileExtension()),
                isForced = info.isForced,
                headers = headers
            )
        }
    }

    private companion object {
        const val TAG = "WebDavGateway"
        const val DEFAULT_PAGE_SIZE = 100
        const val MOVIE_FILE_LIMIT = 3
    }
}

/** `webdav.<sourceId>.<type>` — the catalog id the synthetic addon advertises. */
internal object WebDavCatalogId {
    private const val PREFIX = "webdav."

    data class Parsed(val sourceId: String, val type: ContentType)

    fun format(sourceId: String, type: ContentType): String =
        "$PREFIX$sourceId.${type.toApiString()}"

    fun parse(catalogId: String): Parsed? {
        if (!catalogId.startsWith(PREFIX)) return null
        val body = catalogId.removePrefix(PREFIX)
        val sourceId = body.substringBeforeLast('.', missingDelimiterValue = "")
        val rawType = body.substringAfterLast('.', missingDelimiterValue = "")
        if (sourceId.isBlank() || rawType.isBlank()) return null
        val type = ContentType.fromString(rawType).takeIf { it != ContentType.UNKNOWN } ?: return null
        return Parsed(sourceId = sourceId, type = type)
    }
}
