package com.nuvio.tv.core.player.datasource

import android.content.Context
import com.nuvio.tv.data.local.LocalLibraryPreferences
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Hilt access for the player's static DataSource factory builder, which can't be injected. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerDataSourceEntryPoint {
    fun localLibraryPreferences(): LocalLibraryPreferences
    fun localLibraryCredentialStore(): LocalLibraryCredentialStore

    companion object {
        fun get(context: Context): PlayerDataSourceEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, PlayerDataSourceEntryPoint::class.java)
    }
}
