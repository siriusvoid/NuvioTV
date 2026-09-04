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
import com.nuvio.tv.ui.screens.settings.SettingsTextRow
import com.nuvio.tv.ui.screens.settings.locallibrary.FolderBrowser
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Adds one subtitle folder.
 *
 * Android TV ships no document picker, so the in-app browser the local library
 * uses stands in for one — and reading a subtitle file, which is not a media
 * file, needs all-files access before that browser is any use.
 */
@Composable
fun AddSubtitleFolderScreen(
    onDone: () -> Unit,
    onBackPress: () -> Unit,
    viewModel: SubtitleFoldersViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val context = LocalContext.current
    val addError by viewModel.addError.collectAsStateWithLifecycle()

    var displayName by remember { mutableStateOf("") }
    var pickedPath by remember { mutableStateOf<String?>(null) }
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
        viewModel.clearAddError()
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
    // browser rather than through a form that isn't usable yet.
    var openedBrowserOnEntry by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (openedBrowserOnEntry) return@LaunchedEffect
        openedBrowserOnEntry = true
        browse()
    }

    // Coming back from the browser with a folder in hand, the only thing left to
    // do is save, so put the cursor there instead of making the user find it.
    val saveFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showBrowser, pickedPath) {
        if (!showBrowser && pickedPath != null) {
            runCatching { saveFocusRequester.requestFocus() }
        }
    }

    SettingsStandaloneScaffold(
        title = "Add subtitle folder",
        subtitle = "Pick a folder on this device to scan for subtitle files."
    ) {
        if (showBrowser) {
            FolderBrowser(
                onSelect = { folder ->
                    pickedPath = folder.absolutePath
                    if (displayName.isBlank()) displayName = folder.name.ifBlank { folder.absolutePath }
                    showBrowser = false
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
                title = "New folder",
                subtitle = "Point Nuvio at the folder holding your subtitle packs. Everything " +
                    "below it is scanned, so a parent folder of many releases is fine."
            )

            SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = "Folder") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    SettingsTextRow(
                        label = "Display name",
                        value = displayName,
                        onValueChange = { displayName = it }
                    )
                    Text(
                        text = pickedPath ?: "No folder chosen yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pickedPath == null) {
                            NuvioTheme.colors.TextTertiary
                        } else {
                            NuvioTheme.colors.TextSecondary
                        }
                    )
                    addError?.let { error ->
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
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(if (pickedPath == null) "Choose folder…" else "Change folder…")
                }
                Button(
                    onClick = {
                        pickedPath?.let { path -> viewModel.addSource(path, displayName, onDone) }
                    },
                    enabled = pickedPath != null,
                    modifier = Modifier.focusRequester(saveFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text("Add and scan")
                }
            }
        }
    }
}
