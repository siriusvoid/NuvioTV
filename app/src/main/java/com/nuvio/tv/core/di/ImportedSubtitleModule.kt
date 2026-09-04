package com.nuvio.tv.core.di

import com.nuvio.tv.data.subtitles.ImportedSubtitleManager
import com.nuvio.tv.domain.repository.ImportedSubtitleGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ImportedSubtitleModule {

    @Binds
    @Singleton
    abstract fun bindImportedSubtitleGateway(impl: ImportedSubtitleManager): ImportedSubtitleGateway
}
