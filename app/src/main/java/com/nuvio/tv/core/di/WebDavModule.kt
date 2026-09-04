package com.nuvio.tv.core.di

import com.nuvio.tv.data.webdav.WebDavGatewayImpl
import com.nuvio.tv.domain.repository.WebDavGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WebDavModule {

    @Binds
    @Singleton
    abstract fun bindWebDavGateway(impl: WebDavGatewayImpl): WebDavGateway
}
