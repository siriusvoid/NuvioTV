package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.webdav.WebDavSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile-scoped DataStore for the configured WebDAV sources and their passwords.
 *
 * Passwords sit beside every other secret the app holds (debrid API keys, Trakt
 * tokens) rather than in a keystore, so the WebDAV feature is no weaker and no
 * stronger than the credentials already stored next to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WebDavPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sourcesKey = stringPreferencesKey("sources_json")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val sources: Flow<List<WebDavSource>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { prefs -> parse(prefs[sourcesKey]) }
        }

    suspend fun currentSources(): List<WebDavSource> = sources.first()

    suspend fun upsert(source: WebDavSource) {
        store().edit { prefs ->
            val current = parse(prefs[sourcesKey]).toMutableList()
            val index = current.indexOfFirst { it.id == source.id }
            if (index >= 0) current[index] = source else current.add(source)
            prefs[sourcesKey] = json.encodeToString(current.toList())
        }
    }

    suspend fun remove(sourceId: String) {
        store().edit { prefs ->
            prefs[sourcesKey] = json.encodeToString(
                parse(prefs[sourcesKey]).filterNot { it.id == sourceId }
            )
            prefs.remove(passwordKey(sourceId))
        }
    }

    suspend fun setEnabled(sourceId: String, enabled: Boolean) =
        mutate(sourceId) { it.copy(enabled = enabled) }

    suspend fun setWindowSize(sourceId: String, windowSize: Int) = mutate(sourceId) {
        it.copy(
            windowSize = windowSize.coerceIn(
                WebDavSource.MIN_WINDOW_SIZE,
                WebDavSource.MAX_WINDOW_SIZE
            )
        )
    }

    suspend fun markScanned(sourceId: String, listingCount: Int, scannedAt: Long) =
        mutate(sourceId) { it.copy(lastScanAt = scannedAt, lastListingCount = listingCount) }

    suspend fun password(sourceId: String): String =
        store().data.first()[passwordKey(sourceId)].orEmpty()

    suspend fun savePassword(sourceId: String, password: String) {
        store().edit { prefs -> prefs[passwordKey(sourceId)] = password }
    }

    private suspend fun mutate(sourceId: String, transform: (WebDavSource) -> WebDavSource) {
        store().edit { prefs ->
            val current = parse(prefs[sourcesKey]).toMutableList()
            val index = current.indexOfFirst { it.id == sourceId }
            if (index < 0) return@edit
            current[index] = transform(current[index])
            prefs[sourcesKey] = json.encodeToString(current.toList())
        }
    }

    private fun passwordKey(sourceId: String) = stringPreferencesKey("password_$sourceId")

    private fun parse(payload: String?): List<WebDavSource> {
        if (payload.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<WebDavSource>>(payload)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read stored WebDAV sources — treating as empty", t)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "WebDavPreferences"
        const val FEATURE = "webdav_sources"
    }
}
