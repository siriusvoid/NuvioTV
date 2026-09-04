@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.subtitles

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.storage.AllFilesAccess
import com.nuvio.tv.domain.model.subtitles.isSubtitleFileName
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.locallibrary.FolderBrowser
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Imports a folder of subtitle files for one show.
 *
 * Reached from the show's details page, so the title is already settled and the
 * only thing left to choose is the folder. Android TV ships no document picker,
 * so the same in-app browser the local library uses stands in for one.
 */
@Composable
fun SubtitleImportScreen(
    onBackPress: () -> Unit,
    viewModel: SubtitleImportViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val existingPacks by viewModel.existingPacks.collectAsStateWithLifecycle()

    var showBrowser by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var needsAllFilesAccess by remember { mutableStateOf(false) }
    var grantScreenMissing by remember { mutableStateOf(false) }

    // Only reached below Android 11, where this one permission still covers
    // everything on the volume.
    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, storagePermission) ==
            PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionDenied = !granted
        if (granted) showBrowser = true
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // The grant screen says nothing about what was chosen, so read the real state.
        val granted = AllFilesAccess.isGranted()
        needsAllFilesAccess = !granted
        if (granted) showBrowser = true
    }

    fun requestAllFilesAccess() {
        needsAllFilesAccess = true
        // Each intent is tried in turn: a missing screen only shows up as the launch
        // failing, since package visibility can hide Settings from a resolve query.
        val launched = AllFilesAccess.grantIntents(context).any { intent ->
            runCatching { allFilesLauncher.launch(intent) }.isSuccess
        }
        grantScreenMissing = !launched
    }

    fun browse() {
        permissionDenied = false
        viewModel.clearResult()
        when {
            !AllFilesAccess.isRequired ->
                if (hasStoragePermission()) showBrowser = true else permissionLauncher.launch(storagePermission)

            AllFilesAccess.isGranted() -> {
                needsAllFilesAccess = false
                showBrowser = true
            }

            else -> requestAllFilesAccess()
        }
    }

    // Choosing a folder is the whole point of the screen, so go straight to the
    // browser once the title is known rather than through a page with one button.
    var openedBrowserOnEntry by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.isLoading, state.error) {
        if (openedBrowserOnEntry || state.isLoading || state.error != null) return@LaunchedEffect
        openedBrowserOnEntry = true
        browse()
    }

    // Back from the browser, the next thing to do is read what came of it, so the
    // cursor lands on the button that starts the next import.
    val browseFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showBrowser, state.result) {
        if (!showBrowser && state.result != null) {
            runCatching { browseFocusRequester.requestFocus() }
        }
    }

    SettingsStandaloneScaffold(
        title = "Import subtitles",
        subtitle = state.showName.ifBlank { "Subtitle files stored on this device" }
    ) {
        if (showBrowser) {
            FolderBrowser(
                onSelect = { folder ->
                    showBrowser = false
                    viewModel.import(folder)
                },
                onCancel = { showBrowser = false },
                fileMatcher = { it.isSubtitleFileName() },
                fileNoun = "subtitle file"
            )
            return@SettingsStandaloneScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            SettingsDetailHeader(
                title = state.showName.ifBlank { "Import subtitles" },
                subtitle = "Pick the folder holding this show's subtitle files. They are copied " +
                    "into Nuvio and matched to episodes by their file names."
            )

            SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = "Status") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    Text(
                        text = statusText(state, existingPacks.size, existingPacks.sumOf { it.files.size }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (state.result) {
                            is SubtitleImportResult.Imported -> NuvioTheme.colors.TextPrimary
                            null -> NuvioTheme.colors.TextSecondary
                            else -> NuvioTheme.colors.Error
                        }
                    )
                    state.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.Error
                        )
                    }
                    if (permissionDenied) {
                        Text(
                            text = "Storage permission is required to browse folders. " +
                                "Grant it in Settings → Apps → Nuvio → Permissions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.Error
                        )
                    }
                    if (needsAllFilesAccess) {
                        Text(
                            text = "Nuvio needs \"All files access\" to read subtitle files: " +
                                "Android grants it video files only. Turn it on for Nuvio, then " +
                                "choose the folder again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.Error
                        )
                        if (grantScreenMissing) {
                            Text(
                                text = "This device has no All files access screen. Grant it over " +
                                    "adb instead:\n${AllFilesAccess.adbCommand(context)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                Button(
                    onClick = { browse() },
                    enabled = !state.isLoading && state.error == null && !state.isImporting,
                    modifier = Modifier.focusRequester(browseFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(if (state.result == null) "Choose folder…" else "Import another folder…")
                }
                if (needsAllFilesAccess && !grantScreenMissing) {
                    Button(
                        onClick = { requestAllFilesAccess() },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        Text("Grant access…")
                    }
                }
                Button(
                    onClick = onBackPress,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text("Done")
                }
            }

            if (existingPacks.isNotEmpty()) {
                SettingsGroupCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = "Already imported",
                    subtitle = "Manage these under Settings → Playback → Imported subtitles."
                ) {
                    existingPacks.forEach { pack ->
                        Text(
                            text = listOfNotNull(
                                pack.sourceName?.takeIf { it.isNotBlank() },
                                "${pack.files.size} files, ${pack.matchedCount} matched"
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

private fun statusText(
    state: SubtitleImportUiState,
    packCount: Int,
    fileCount: Int
): String = when {
    state.isLoading -> "Loading this title…"
    state.isImporting -> "Importing…"
    else -> when (val result = state.result) {
        is SubtitleImportResult.Imported ->
            "Imported ${result.fileCount} file(s) from \"${result.folderName}\" — " +
                "${result.matchedCount} matched to an episode."

        SubtitleImportResult.NoSubtitleFiles ->
            "That folder holds no subtitle files Nuvio can read."

        SubtitleImportResult.Failed ->
            "The import failed. Check the folder is still readable and try again."

        null -> if (packCount > 0) {
            "$fileCount file(s) already imported for this title."
        } else {
            "Nothing imported for this title yet."
        }
    }
}
