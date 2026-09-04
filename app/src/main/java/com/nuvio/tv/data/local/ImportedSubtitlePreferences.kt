package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile-scoped index of the imported subtitle packs.
 *
 * Only the index is stored here; the files themselves sit in
 * [com.nuvio.tv.data.subtitles.ImportedSubtitleStorage].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ImportedSubtitlePreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val packsKey = stringPreferencesKey("packs_json")

    val packs: Flow<List<ImportedSubtitlePack>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { prefs -> parse(prefs[packsKey]) }
        }

    suspend fun save(packs: List<ImportedSubtitlePack>) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            prefs[packsKey] = json.encodeToString(packs)
        }
    }

    private fun parse(payload: String?): List<ImportedSubtitlePack> {
        if (payload.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ImportedSubtitlePack>>(payload) }
            .getOrElse { error ->
                Log.w(TAG, "Could not read the imported subtitle index", error)
                emptyList()
            }
    }

    companion object {
        private const val FEATURE = "imported_subtitle_preferences"
        private const val TAG = "ImportedSubtitlePrefs"
    }
}
