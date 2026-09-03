package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow

/** Where the repositories short-circuit for the synthetic addon or ids with [LOCAL_ID_PREFIX]. */
interface LocalLibraryGateway {
    /** The synthetic addon for enabled sources, one catalog per section; null when none are enabled. */
    fun synthesizeAddon(): Flow<Addon?>

    /** Whether [baseUrl] / [addonId] refers to the synthetic local-library addon. */
    fun isLocalLibrary(addonId: String?, baseUrl: String?): Boolean

    /** Whether [id] refers to a local-library item (catalog item or meta id). */
    fun isLocalId(id: String?): Boolean

    /** TMDB to IMDB ids from stored matches, so tracker entries line up with no network. */
    suspend fun resolvedImdbIds(): Map<Int, String>

    suspend fun catalog(catalogId: String, skip: Int, skipStep: Int): NetworkResult<CatalogRow>

    suspend fun meta(type: String, id: String): NetworkResult<Meta>

    suspend fun streams(type: String, id: String, season: Int?, episode: Int?): NetworkResult<List<Stream>>

    companion object {
        const val ADDON_ID = "local-library"
        const val SYNTHETIC_BASE_URL = "nuvio-local://"
        const val LOCAL_ID_PREFIX = "nuvio-local:"
    }
}
