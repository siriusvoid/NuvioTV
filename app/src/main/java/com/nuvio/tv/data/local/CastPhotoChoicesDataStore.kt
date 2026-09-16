package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val Context.castPhotoChoicesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cast_photo_choices_store",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/** Cast pictures the user chose to hide or show, shared by every profile. */
@Singleton
class CastPhotoChoicesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hiddenKey = stringSetPreferencesKey("cast_photos_hidden")
    private val shownKey = stringSetPreferencesKey("cast_photos_shown")

    /** Photo URL to hidden; null until the first read. */
    val overrides: StateFlow<Map<String, Boolean>?> = context.castPhotoChoicesDataStore.data
        .map { prefs ->
            buildMap {
                prefs[shownKey]?.forEach { put(it, false) }
                prefs[hiddenKey]?.forEach { put(it, true) }
            }
        }
        .stateIn(scope, SharingStarted.Lazily, null)

    suspend fun setHidden(url: String, hidden: Boolean) {
        context.castPhotoChoicesDataStore.edit { prefs ->
            val (addKey, removeKey) = if (hidden) hiddenKey to shownKey else shownKey to hiddenKey
            prefs[addKey] = prefs[addKey].orEmpty() + url
            prefs[removeKey] = prefs[removeKey].orEmpty() - url
        }
    }
}
