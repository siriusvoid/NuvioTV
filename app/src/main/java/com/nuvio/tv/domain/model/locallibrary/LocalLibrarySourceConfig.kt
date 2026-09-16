package com.nuvio.tv.domain.model.locallibrary

import androidx.compose.runtime.Immutable

/**
 * A user-configured source backing the synthetic Local Library addon.
 *
 */
@Immutable
data class LocalLibrarySourceConfig(
    val id: String,
    val displayName: String,
    val kind: SourceKind,
    /** A SAF tree URI where there's a picker, or a file:// path from the in-app browser on TV. */
    val urlOrPath: String,
    /** Non-secret per-backend parameters, kept generic. */
    val params: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val lastScanAt: Long? = null,
    val itemCount: Int = 0
)
