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
    /**
     * The persisted SAF tree URI (content://…) on devices with a document
     * picker, or a file:// path picked via the in-app folder browser on
     * Android TV / Google TV.
     */
    val urlOrPath: String,
    /**
     * Optional non-secret auxiliary parameters. Kept generic so we don't need
     * a new field per backend.
     */
    val params: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val lastScanAt: Long? = null,
    val itemCount: Int = 0
)
