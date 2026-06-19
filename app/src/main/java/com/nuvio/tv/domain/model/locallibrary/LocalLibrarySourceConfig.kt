package com.nuvio.tv.domain.model.locallibrary

import androidx.compose.runtime.Immutable

/**
 * A user-configured source backing the synthetic Local Library addon.
 * Secrets live in the encrypted credential store under [id].
 */
@Immutable
data class LocalLibrarySourceConfig(
    val id: String,
    val displayName: String,
    val kind: SourceKind,
    /** Jellyfin server url, SMB share uri, or a SAF tree uri / file:// path from the in-app browser on TV. */
    val urlOrPath: String,
    /** Non-secret per-backend parameters (Jellyfin user id, SMB domain), kept generic. */
    val params: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val lastScanAt: Long? = null,
    val itemCount: Int = 0
)
