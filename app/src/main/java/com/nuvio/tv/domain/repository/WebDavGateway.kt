package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow

/**
 * Bridge between the Catalog/Stream repositories and the WebDAV library.
 *
 * The library is exposed as a synthetic addon, the same shape the local library
 * uses, with one difference that matters throughout: WebDAV items keep the id
 * space the user's metadata addon serves (`tt0972656`, `kitsu:6480`, …) rather
 * than minting their own. So this gateway answers `catalog` and `stream` only —
 * `meta`, the details page, Library and Continue Watching all keep working
 * through the normal addons, and a debrid copy shows up as one more source on a
 * show the user already has.
 *
 * Because the ids are not its own, the synthetic addon declares no id prefixes
 * and is therefore asked about every title the user opens.
 */
interface WebDavGateway {
    /**
     * Emits the synthetic Addon describing the enabled sources, with one catalog
     * per source and type. Emits null while no source is enabled so callers can
     * leave it out of the addon list.
     */
    fun synthesizeAddon(): Flow<Addon?>

    /** Whether [addonId] / [baseUrl] refers to the synthetic WebDAV addon. */
    fun isWebDavAddon(addonId: String?, baseUrl: String?): Boolean

    suspend fun catalog(
        catalogId: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String> = emptyMap()
    ): NetworkResult<CatalogRow>

    /**
     * Files the library holds for [videoId]. Empty rather than an error when the
     * library simply has nothing for it, which is the common case.
     */
    suspend fun streams(type: String, videoId: String): NetworkResult<List<Stream>>

    /** Rescans every enabled source, once per process. */
    fun scanOnLaunch()

    companion object {
        const val ADDON_ID = "nuvio.webdav.library"
        const val ADDON_NAME = "WebDAV library"
        const val SYNTHETIC_BASE_URL = "nuvio-webdav://"
    }
}
