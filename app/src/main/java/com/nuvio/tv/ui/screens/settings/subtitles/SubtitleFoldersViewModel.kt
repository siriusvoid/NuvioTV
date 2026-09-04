package com.nuvio.tv.ui.screens.settings.subtitles

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import com.nuvio.tv.domain.model.subtitles.SubtitleFolderSource
import com.nuvio.tv.domain.model.subtitles.SubtitleScanProgress
import com.nuvio.tv.domain.model.subtitles.UnmatchedSubtitleFolder
import com.nuvio.tv.domain.repository.ImportedSubtitleGateway
import com.nuvio.tv.ui.util.quantityStringResource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubtitleFoldersViewModel @Inject constructor(
    private val importedSubtitles: ImportedSubtitleGateway
) : ViewModel() {

    val sources: StateFlow<List<SubtitleFolderSource>> = importedSubtitles.sources
    val packs: StateFlow<List<ImportedSubtitlePack>> = importedSubtitles.packs
    val unmatched: StateFlow<List<UnmatchedSubtitleFolder>> = importedSubtitles.unmatched
    val progress: StateFlow<Map<String, SubtitleScanProgress>> = importedSubtitles.progress

    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    fun addSource(path: String, displayName: String, onAdded: () -> Unit) {
        viewModelScope.launch {
            importedSubtitles.addSource(path, displayName)
                .onSuccess { _addError.value = null; onAdded() }
                // Blank shows the generic failure text.
                .onFailure { error -> _addError.value = error.message.orEmpty() }
        }
    }

    fun clearAddError() {
        _addError.value = null
    }

    fun rescan(sourceId: String) = importedSubtitles.rescan(sourceId)

    fun rescanAll() = importedSubtitles.rescanAll()

    fun setEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch { importedSubtitles.setEnabled(sourceId, enabled) }
    }

    /** The source screen leaves on its own once the source is gone from [sources]. */
    fun removeSource(sourceId: String) {
        viewModelScope.launch { importedSubtitles.removeSource(sourceId) }
    }
}

/** "Scanning… 12 releases found" and friends, shared by the list and detail screens. */
@Composable
internal fun scanStatusText(progress: SubtitleScanProgress?, source: SubtitleFolderSource, packs: Int, files: Int): String =
    when (progress) {
        is SubtitleScanProgress.Scanning ->
            quantityStringResource(R.plurals.subtitle_folders_scanning, progress.foldersFound)
        is SubtitleScanProgress.Matching -> stringResource(R.string.library_scan_matching, progress.done, progress.total)
        is SubtitleScanProgress.Failed -> stringResource(R.string.library_scan_failed, progress.reason)
        SubtitleScanProgress.Idle, null ->
            if (source.lastScanAt == null) {
                stringResource(R.string.subtitle_folders_not_scanned)
            } else {
                releasesAndFiles(packs, files)
            }
    }

@Composable
internal fun releasesAndFiles(packs: Int, files: Int): String =
    quantityStringResource(R.plurals.subtitle_folders_releases, packs) + " · " +
        quantityStringResource(R.plurals.library_files, files)
