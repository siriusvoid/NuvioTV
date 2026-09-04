package com.nuvio.tv.ui.screens.settings.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.webdav.AnimeSearchHit
import com.nuvio.tv.data.webdav.WebDavManager
import com.nuvio.tv.data.webdav.WebDavSourceCounts
import com.nuvio.tv.domain.model.webdav.WebDavConnectionResult
import com.nuvio.tv.domain.model.webdav.WebDavProvider
import com.nuvio.tv.domain.model.webdav.WebDavReviewRow
import com.nuvio.tv.domain.model.webdav.WebDavScanProgress
import com.nuvio.tv.domain.model.webdav.WebDavSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class WebDavSettingsViewModel @Inject constructor(
    private val manager: WebDavManager,
    debridSettings: DebridSettingsDataStore
) : ViewModel() {

    data class UiState(
        val sources: List<WebDavSource> = emptyList(),
        val progress: Map<String, WebDavScanProgress> = emptyMap(),
        val counts: Map<String, WebDavSourceCounts> = emptyMap()
    )

    val uiState: StateFlow<UiState> = combine(
        manager.sources,
        manager.progress,
        manager.counts
    ) { sources, progress, counts -> UiState(sources, progress, counts) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val _addResult = MutableStateFlow<AddResult?>(null)
    val addResult: StateFlow<AddResult?> = _addResult.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _reviewRows = MutableStateFlow<List<WebDavReviewRow>>(emptyList())
    val reviewRows: StateFlow<List<WebDavReviewRow>> = _reviewRows.asStateFlow()

    private val _searchResults = MutableStateFlow<List<AnimeSearchHit>>(emptyList())
    val searchResults: StateFlow<List<AnimeSearchHit>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /**
     * TorBox signs WebDAV in with the same API key the debrid settings already hold,
     * and typing one on a remote is miserable, so the add form offers to reuse it.
     */
    val storedTorboxApiKey: StateFlow<String> = debridSettings.settings
        .map { it.torboxApiKey }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ---------------------------------------------------------------- sources

    fun testConnection(
        provider: WebDavProvider,
        baseUrl: String,
        username: String,
        password: String,
        rootPath: String
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _addResult.value = when (
                val result = manager.testConnection(
                    baseUrl = baseUrl,
                    username = provider.fixedUsername ?: username,
                    password = password,
                    rootPath = rootPath
                )
            ) {
                is WebDavConnectionResult.Success ->
                    AddResult.Message("Connected. Found ${result.entryCount} entries.")

                is WebDavConnectionResult.Failure -> AddResult.Failure(result.message)
            }
            _busy.value = false
        }
    }

    fun addSource(
        provider: WebDavProvider,
        displayName: String,
        baseUrl: String,
        username: String,
        password: String,
        rootPath: String
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = manager.addSource(
                provider = provider,
                displayName = displayName,
                baseUrl = baseUrl,
                username = username,
                password = password,
                rootPath = rootPath
            )
            _addResult.value = result.fold(
                onSuccess = { AddResult.Success },
                onFailure = {
                    AddResult.Failure(it.message ?: "Could not add the source.")
                }
            )
            _busy.value = false
        }
    }

    fun clearAddResult() {
        _addResult.value = null
    }

    fun scan(sourceId: String) = manager.scan(sourceId)

    fun scanAll() = manager.rescanAll()

    fun rebuild(sourceId: String) = manager.rebuild(sourceId)

    fun setEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch { manager.setEnabled(sourceId, enabled) }
    }

    fun setWindowSize(sourceId: String, windowSize: Int) {
        viewModelScope.launch { manager.setWindowSize(sourceId, windowSize) }
    }

    /** [onRemoved] runs after the delete lands, so callers navigate away only then. */
    fun removeSource(sourceId: String, onRemoved: () -> Unit = {}) {
        viewModelScope.launch {
            manager.removeSource(sourceId)
            onRemoved()
        }
    }

    // ----------------------------------------------------------------- review

    fun loadReviewRows(sourceId: String) {
        viewModelScope.launch { _reviewRows.value = manager.reviewRows(sourceId) }
    }

    fun search(query: String) {
        if (_searching.value) return
        viewModelScope.launch {
            _searching.value = true
            _searchResults.value = manager.searchForOverride(query)
            _searching.value = false
        }
    }

    fun toggleExcluded(row: WebDavReviewRow) {
        val sourceId = row.sourceId
        viewModelScope.launch {
            manager.setExcluded(row.folderKey, excluded = row.match?.excluded != true)
            _reviewRows.value = manager.reviewRows(sourceId)
        }
    }

    fun rematch(row: WebDavReviewRow) {
        val sourceId = row.sourceId
        viewModelScope.launch {
            manager.rematch(row.folderKey)
            _reviewRows.value = manager.reviewRows(sourceId)
        }
    }

    /**
     * [onSaved] runs once the override is written, and callers navigate from there
     * rather than immediately: leaving the picker destroys its back stack entry,
     * which would cancel this scope mid-write and leave the folder as it was.
     */
    fun applyOverride(
        sourceId: String,
        folderKey: String,
        hit: AnimeSearchHit,
        season: Int?,
        treatAsMovie: Boolean,
        onSaved: () -> Unit = {}
    ) {
        viewModelScope.launch {
            manager.applyOverride(
                folderKey = folderKey,
                hit = hit,
                season = season,
                treatAsMovie = treatAsMovie
            )
            _reviewRows.value = manager.reviewRows(sourceId)
            onSaved()
        }
    }

    sealed interface AddResult {
        data object Success : AddResult
        data class Message(val text: String) : AddResult
        data class Failure(val message: String) : AddResult
    }
}
