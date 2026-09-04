package com.nuvio.tv.ui.screens.settings.subtitles

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import com.nuvio.tv.domain.repository.ImportedSubtitleGateway
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Imports a folder of subtitle files against the show its details page named. */
@HiltViewModel
class SubtitleImportViewModel @Inject constructor(
    private val metaRepository: MetaRepository,
    private val importedSubtitles: ImportedSubtitleGateway,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val itemType: String = savedStateHandle["itemType"] ?: ""
    private val itemId: String = savedStateHandle["itemId"] ?: ""

    private val _uiState = MutableStateFlow(SubtitleImportUiState())
    val uiState: StateFlow<SubtitleImportUiState> = _uiState.asStateFlow()

    val existingPacks: StateFlow<List<ImportedSubtitlePack>> = importedSubtitles.packs
        .map { packs -> packs.filter { it.metaId == itemId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var meta: Meta? = null

    init {
        loadMeta()
    }

    private fun loadMeta() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                metaRepository.getMetaFromAllAddons(itemType, itemId)
                    .first { it !is NetworkResult.Loading }
            }.getOrElse { error ->
                Log.w(TAG, "Could not load $itemType/$itemId", error)
                null
            }
            meta = (result as? NetworkResult.Success)?.data
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    showName = meta?.name.orEmpty(),
                    error = if (meta == null) {
                        "Could not load this title. Go back and try again."
                    } else {
                        null
                    }
                )
            }
        }
    }

    fun import(folder: File) {
        val target = meta ?: return
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true, result = null) }
        viewModelScope.launch {
            val imported = runCatching { importedSubtitles.import(target, folder) }
                .getOrElse { error ->
                    Log.w(TAG, "Import from ${folder.absolutePath} failed", error)
                    null
                }
            // The pack that just landed is the newest one this show has.
            val matched = importedSubtitles.packsFor(target.id)
                .maxByOrNull { it.importedAt }
                ?.matchedCount
                ?: 0
            _uiState.update { state ->
                state.copy(
                    isImporting = false,
                    result = when {
                        imported == null -> SubtitleImportResult.Failed
                        imported == 0 -> SubtitleImportResult.NoSubtitleFiles
                        else -> SubtitleImportResult.Imported(
                            fileCount = imported,
                            matchedCount = matched,
                            folderName = folder.name
                        )
                    }
                )
            }
        }
    }

    fun clearResult() {
        _uiState.update { it.copy(result = null) }
    }

    companion object {
        private const val TAG = "SubtitleImport"
    }
}

data class SubtitleImportUiState(
    val isLoading: Boolean = true,
    val showName: String = "",
    val error: String? = null,
    val isImporting: Boolean = false,
    val result: SubtitleImportResult? = null
)

sealed interface SubtitleImportResult {
    data class Imported(
        val fileCount: Int,
        val matchedCount: Int,
        val folderName: String
    ) : SubtitleImportResult

    data object NoSubtitleFiles : SubtitleImportResult

    data object Failed : SubtitleImportResult
}
