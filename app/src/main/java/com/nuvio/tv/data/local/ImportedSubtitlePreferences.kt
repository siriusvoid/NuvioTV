package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import com.nuvio.tv.domain.model.subtitles.SubtitleFolderSource
import com.nuvio.tv.domain.model.subtitles.UnmatchedSubtitleFolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Everything one profile knows about its subtitle folders. */
@Serializable
data class ImportedSubtitleIndex(
    val sources: List<SubtitleFolderSource> = emptyList(),
    val packs: List<ImportedSubtitlePack> = emptyList(),
    val unmatched: List<UnmatchedSubtitleFolder> = emptyList()
)

/**
 * Profile-scoped index of the subtitle folders and what scanning them found.
 *
 * Only the index lives here. The subtitle files themselves stay where the user
 * put them, so nothing in this store owns any data that cannot be rebuilt by a
 * rescan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ImportedSubtitlePreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val indexKey = stringPreferencesKey("index_json")

    val index: Flow<ImportedSubtitleIndex> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { prefs -> parse(prefs[indexKey]) }
        }

    suspend fun current(): ImportedSubtitleIndex = index.first()

    suspend fun save(index: ImportedSubtitleIndex) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            prefs[indexKey] = json.encodeToString(index)
        }
    }

    private fun parse(payload: String?): ImportedSubtitleIndex {
        if (payload.isNullOrBlank()) return ImportedSubtitleIndex()
        return runCatching { json.decodeFromString<ImportedSubtitleIndex>(payload) }
            .getOrElse { error ->
                Log.w(TAG, "Could not read the subtitle folder index", error)
                ImportedSubtitleIndex()
            }
    }

    companion object {
        private const val FEATURE = "imported_subtitle_preferences"
        private const val TAG = "ImportedSubtitlePrefs"
    }
}
