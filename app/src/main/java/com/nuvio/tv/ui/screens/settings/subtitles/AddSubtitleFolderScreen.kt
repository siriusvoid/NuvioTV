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
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.storage.AllFilesAccess
import com.nuvio.tv.domain.model.subtitles.isSubtitleFileName
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsTextRow
import com.nuvio.tv.ui.screens.settings.locallibrary.FolderBrowser
import com.nuvio.tv.ui.theme.NuvioTheme

/** Adds a subtitle folder via the in-app browser; reading non-media files needs all-files access first. */
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

    // Only requested below Android 11, where it still covers the whole volume.
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
        // Try each intent: a missing screen only shows as a failed launch, since visibility can hide Settings.
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

    // Nothing works without a folder, so open the browser on arrival.
    var openedBrowserOnEntry by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (openedBrowserOnEntry) return@LaunchedEffect
        openedBrowserOnEntry = true
        browse()
    }

    // Back from the browser with a folder, saving is all that's left, so focus Save.
    val saveFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showBrowser, pickedPath) {
        if (!showBrowser && pickedPath != null) {
            runCatching { saveFocusRequester.requestFocus() }
        }
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.subtitle_folders_add_title),
        subtitle = stringResource(R.string.subtitle_folders_add_desc)
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
                filesHere = R.plurals.folder_browser_subtitle_files
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
                title = stringResource(R.string.subtitle_folders_new),
                subtitle = stringResource(R.string.subtitle_folders_new_desc)
            )

            SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.subtitle_folders_folder)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    SettingsTextRow(
                        label = stringResource(R.string.library_display_name),
                        value = displayName,
                        onValueChange = { displayName = it }
                    )
                    Text(
                        text = pickedPath ?: stringResource(R.string.library_folder_none_chosen),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pickedPath == null) {
                            NuvioTheme.colors.TextTertiary
                        } else {
                            NuvioTheme.colors.TextSecondary
                        }
                    )
                    addError?.let { error ->
                        Text(
                            text = error.ifBlank { stringResource(R.string.subtitle_folders_add_failed) },
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.Error
                        )
                    }
                    if (permissionDenied) {
                        Text(
                            text = stringResource(R.string.library_storage_permission_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.Error
                        )
                    }
                    if (needsAllFilesAccess) {
                        Text(
                            text = stringResource(R.string.subtitle_folders_all_files_access),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.Error
                        )
                        if (grantScreenMissing) {
                            Text(
                                text = stringResource(
                                    R.string.subtitle_folders_all_files_access_adb,
                                    AllFilesAccess.adbCommand(context)
                                ),
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
                    Text(
                        stringResource(
                            if (pickedPath == null) R.string.library_folder_choose else R.string.library_folder_change
                        )
                    )
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
                    Text(stringResource(R.string.subtitle_folders_add_and_scan))
                }
            }
        }
    }
}
