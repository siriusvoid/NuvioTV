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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
        title = "Add source",
        subtitle = "Pick a folder on this device to scan for media."
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
    var displayName by remember { mutableStateOf("On-device files") }
    var pickedPath by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    // Google TV / Android TV ships no SAF document picker, so folders are chosen
    // via the in-app browser, which reads the filesystem directly and therefore
    // needs the media-read runtime permission.
    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, storagePermission) ==
            PackageManager.PERMISSION_GRANTED

    // Coming back from the browser with a folder in hand, the only thing left to
    // do is save, so put the cursor there instead of making the user find it.
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

    // Choosing a folder is the whole point of the screen and nothing here can be
    // done without one, so go straight to the browser on arrival rather than
    // making the user press through a form that isn't usable yet.
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
                if (displayName.isBlank() || displayName == "On-device files") {
                    displayName = folder.name.ifBlank { "On-device files" }
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
            title = "New source",
            subtitle = "Name the source and choose the folder Nuvio should index."
        )

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = "Source"
        ) {
            // One column owns every gap in this card. The group card spaces its own
            // children by 2dp, which left the path crowded against the field, and
            // padding each child instead made the gaps add up to different sizes.
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
                if (permissionDenied) {
                    Text(
                        text = "Storage permission is required to browse folders. " +
                            "Grant it in Settings → Apps → Nuvio → Permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error
                    )
                }
            }
        }

        // No settingsOptionRow on this row: its focusRestorer restores the
        // last-focused button whenever focus re-enters, which overrides the
        // request to Test & save above.
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
                Text(if (pickedPath == null) "Choose folder…" else "Change folder…")
            }
            Button(
                onClick = {
                    pickedPath?.let {
                        viewModel.addLocalFile(displayName, Uri.fromFile(File(it)).toString())
                    }
                },
                enabled = pickedPath != null,
                modifier = Modifier.focusRequester(saveFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text("Test & save")
            }
        }

        ResultBanner(addResult)
    }
}

@Composable
private fun ResultBanner(result: LocalLibrarySettingsViewModel.AddResult?) {
    if (result is LocalLibrarySettingsViewModel.AddResult.Failure) {
        Text(
            text = result.message,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.Error
        )
    }
}
