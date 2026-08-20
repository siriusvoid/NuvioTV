package com.nuvio.tv.data.locallibrary.source

import android.content.Context
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.SourceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Sources aren't singletons: each is bound to one config and cheap to build. */
@Singleton
class LocalLibrarySourceFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun create(config: LocalLibrarySourceConfig): LocalLibrarySource = when (config.kind) {
        SourceKind.LOCAL_FILE -> LocalFileSource(config, context)
    }
}
