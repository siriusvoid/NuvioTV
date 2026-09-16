@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsTextRow
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File

@Composable
fun AddSourceScreen(
    onDone: () -> Unit,
    onBackPress: () -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val addResult by viewModel.addResult.collectAsStateWithLifecycle()

    LaunchedEffect(addResult) {
        if (addResult is LocalLibrarySettingsViewModel.AddResult.Success) {
            viewModel.clearAddResult()
            onDone()
        }
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.local_library_add_source_title),
        subtitle = stringResource(R.string.local_library_add_source_desc)
    ) {
        LocalFileForm(viewModel, addResult)
    }
}

@Composable
private fun LocalFileForm(
    viewModel: LocalLibrarySettingsViewModel,
    addResult: LocalLibrarySettingsViewModel.AddResult?
) {
    val context = LocalContext.current
    val defaultName = stringResource(R.string.local_library_default_name)
    var displayName by remember { mutableStateOf(defaultName) }
    var pickedPath by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    // TV has no SAF picker, so the in-app browser reads the filesystem and needs the media-read permission.
    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, storagePermission) ==
            PackageManager.PERMISSION_GRANTED

    // Back from the browser with a folder, saving is all that's left, so focus Save.
    val saveFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showBrowser, pickedPath) {
        if (!showBrowser && pickedPath != null) {
            runCatching { saveFocusRequester.requestFocus() }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionDenied = !granted
        if (granted) showBrowser = true
    }

    // Nothing works without a folder, so open the browser on arrival.
    var openedBrowserOnEntry by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (openedBrowserOnEntry) return@LaunchedEffect
        openedBrowserOnEntry = true
        if (hasStoragePermission()) showBrowser = true
        else permissionLauncher.launch(storagePermission)
    }

    if (showBrowser) {
        FolderBrowser(
            onSelect = { folder ->
                pickedPath = folder.absolutePath
                if (displayName.isBlank() || displayName == defaultName) {
                    displayName = folder.name.ifBlank { defaultName }
                }
                showBrowser = false
            },
            onCancel = { showBrowser = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.library_source_new),
            subtitle = stringResource(R.string.local_library_new_source_desc)
        )

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.library_source)
        ) {
            // One column owns every gap here; the group card's tight child spacing crowds the fields.
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
                if (permissionDenied) {
                    Text(
                        text = stringResource(R.string.library_storage_permission_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error
                    )
                }
            }
        }

        // No settingsOptionRow: its focusRestorer would override the request to focus Test & save.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Button(
                onClick = {
                    permissionDenied = false
                    if (hasStoragePermission()) showBrowser = true
                    else permissionLauncher.launch(storagePermission)
                },
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
                    pickedPath?.let {
                        viewModel.addLocalFile(
                            displayName.ifBlank { defaultName },
                            Uri.fromFile(File(it)).toString()
                        )
                    }
                },
                enabled = pickedPath != null,
                modifier = Modifier.focusRequester(saveFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.library_test_and_save))
            }
        }

        ResultBanner(addResult)
    }
}

@Composable
private fun ResultBanner(result: LocalLibrarySettingsViewModel.AddResult?) {
    if (result is LocalLibrarySettingsViewModel.AddResult.Failure) {
        Text(
            text = result.message ?: stringResource(R.string.library_source_add_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.Error
        )
    }
}
