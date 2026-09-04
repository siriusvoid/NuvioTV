package com.nuvio.tv.ui.screens.settings.subtitles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.subtitles.ImportedSubtitlePack
import com.nuvio.tv.domain.repository.ImportedSubtitleGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Manages the imported subtitle packs listed under Playback settings. */
@HiltViewModel
class ImportedSubtitlesViewModel @Inject constructor(
    private val importedSubtitles: ImportedSubtitleGateway
) : ViewModel() {

    val packs: StateFlow<List<ImportedSubtitlePack>> = importedSubtitles.packs

    fun setKeepAfterWatching(packId: String, keep: Boolean) {
        viewModelScope.launch { importedSubtitles.setKeepAfterWatching(packId, keep) }
    }

    /** The pack screen leaves on its own once the pack is gone from [packs]. */
    fun deletePack(packId: String) {
        viewModelScope.launch { importedSubtitles.deletePack(packId) }
    }
}
