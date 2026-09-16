@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
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
                TextRow(
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

/**
 * Inline click-to-edit text input for TV.
 *
 * Mirrors the proven pattern in `AddonManagerScreen.kt:273` — a focusable TV
 * `Surface` always wraps a permanently-mounted `BasicTextField`. During D-pad
 * navigation the Surface owns focus (so the IME stays closed). Pressing select
 * fires `Surface.onClick`, which flips [editing] true; a `LaunchedEffect`
 * then calls `requestFocus()` on the inner field and `keyboardController.show()`.
 * Losing focus or pressing Done flips it back, and the system hides the IME.
 *
 * Why this works where my prior attempts didn't: TV `Card.onClick` doesn't
 * cleanly hand focus to an inner BasicTextField, and conditional rendering of
 * the field meant the focusRequester wasn't laid out when `requestFocus()` ran.
 */
@Composable
private fun TextRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text
) {
    var editing by remember { mutableStateOf(false) }
    val surfaceFocusRequester = remember { FocusRequester() }
    val fieldFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val fieldShape = RoundedCornerShape(10.dp)

    LaunchedEffect(editing) {
        if (editing) {
            fieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextSecondary
        )
        Surface(
            onClick = { editing = true },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(surfaceFocusRequester),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = fieldShape
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = fieldShape
                )
            ),
            shape = ClickableSurfaceDefaults.shape(fieldShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Box(
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = NuvioTheme.spacing.md
                )
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    visualTransformation = if (isPassword) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary
                    ),
                    cursorBrush = SolidColor(
                        if (editing) NuvioTheme.colors.Primary else Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboard,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        editing = false
                        keyboardController?.hide()
                        surfaceFocusRequester.requestFocus()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocusRequester)
                        .onFocusChanged { state ->
                            if (!state.isFocused && editing) {
                                editing = false
                                keyboardController?.hide()
                            }
                        },
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                text = "Tap to edit",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        inner()
                    }
                )
            }
        }
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
