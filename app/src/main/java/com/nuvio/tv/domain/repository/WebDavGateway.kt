package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow

/** The WebDAV library as a synthetic addon serving only catalog and stream; items keep the addon's ids. */
interface WebDavGateway {
    /** The synthetic addon, one catalog per source and type; null while no source is enabled. */
    fun synthesizeAddon(): Flow<Addon?>

    /** Whether [addonId] / [baseUrl] refers to the synthetic WebDAV addon. */
    fun isWebDavAddon(addonId: String?, baseUrl: String?): Boolean

    suspend fun catalog(
        catalogId: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String> = emptyMap()
    ): NetworkResult<CatalogRow>

    /** Files for [videoId]; empty, not an error, when the library has none. */
    suspend fun streams(type: String, videoId: String): NetworkResult<List<Stream>>

    /** Rescans every enabled source, once per process. */
    fun scanOnLaunch()

    companion object {
        const val ADDON_ID = "nuvio.webdav.library"
        const val ADDON_NAME = "WebDAV library"
        const val SYNTHETIC_BASE_URL = "nuvio-webdav://"
    }
}
