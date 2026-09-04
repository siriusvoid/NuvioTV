package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nuvio.tv.ui.util.languageCodeToName

@Immutable
data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String?,
    val isStreamProvided: Boolean = false
) {
    /**
     * Served off this device — an imported subtitle file rather than a url to
     * fetch. Those are read straight off disk instead of over HTTP.
     */
    val isLocalFile: Boolean get() = url.startsWith("file:", ignoreCase = true)

    fun getDisplayLanguage(): String = languageCodeToName(lang)

    companion object {
        fun languageCodeToName(code: String): String = com.nuvio.tv.ui.util.languageCodeToName(code)
    }
}
