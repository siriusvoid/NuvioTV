@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.webdav

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.webdav.WebDavProvider
import com.nuvio.tv.ui.screens.settings.SettingsChoiceChip
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsTextRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleChip
import com.nuvio.tv.ui.screens.settings.SettingsVerbatimKeyboard
import com.nuvio.tv.ui.screens.settings.settingsOptionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.quantityStringResource

@Composable
internal fun AddWebDavSourceScreen(
    onDone: () -> Unit,
    onBackPress: () -> Unit,
    viewModel: WebDavSettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val addResult by viewModel.addResult.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val storedTorboxApiKey by viewModel.storedTorboxApiKey.collectAsStateWithLifecycle()

    LaunchedEffect(addResult) {
        if (addResult is WebDavSettingsViewModel.AddResult.Success) {
            viewModel.clearAddResult()
            onDone()
        }
    }
    // A leftover message would read as a verdict on a form not yet filled in.
    LaunchedEffect(Unit) { viewModel.clearAddResult() }

    var providerId by rememberSaveable { mutableStateOf(WebDavProvider.REAL_DEBRID.id) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf(WebDavProvider.REAL_DEBRID.defaultBaseUrl) }
    var rootPath by rememberSaveable { mutableStateOf(WebDavProvider.REAL_DEBRID.defaultRootPath) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val provider = WebDavProvider.fromId(providerId)
    val providerLabel = provider.label()
    val firstProviderChip = remember { FocusRequester() }

    fun applyProvider(next: WebDavProvider) {
        providerId = next.id
        baseUrl = next.defaultBaseUrl
        rootPath = next.defaultRootPath
        username = next.fixedUsername.orEmpty()
        viewModel.clearAddResult()
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.webdav_add_title),
        subtitle = stringResource(R.string.webdav_add_desc)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            SettingsDetailHeader(
                title = stringResource(R.string.library_source_new),
                subtitle = stringResource(R.string.webdav_new_source_desc)
            )

            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.webdav_provider)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsOptionRow(firstProviderChip),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    WebDavProvider.entries.forEachIndexed { index, option ->
                        SettingsToggleChip(
                            label = option.label(),
                            selected = option == provider,
                            onClick = { applyProvider(option) },
                            modifier = if (index == 0) {
                                Modifier.focusRequester(firstProviderChip)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }

            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.webdav_connection)
            ) {
                // One column owns every gap here; the group card's tight child spacing crowds the fields.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    SettingsTextRow(
                        label = stringResource(R.string.library_display_name),
                        value = displayName,
                        onValueChange = { displayName = it },
                        placeholder = providerLabel
                    )
                    SettingsTextRow(
                        label = stringResource(R.string.webdav_server_address),
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        keyboardOptions = SettingsVerbatimKeyboard
                    )
                    SettingsTextRow(
                        label = stringResource(R.string.webdav_folder_path),
                        value = rootPath,
                        onValueChange = { rootPath = it },
                        placeholder = stringResource(R.string.webdav_server_root),
                        keyboardOptions = SettingsVerbatimKeyboard
                    )
                    SettingsTextRow(
                        label = stringResource(R.string.webdav_username),
                        value = username,
                        onValueChange = { username = it },
                        enabled = provider.fixedUsername == null,
                        keyboardOptions = SettingsVerbatimKeyboard
                    )
                    SettingsTextRow(
                        label = stringResource(
                            if (provider == WebDavProvider.TORBOX) R.string.webdav_api_key else R.string.webdav_password
                        ),
                        value = password,
                        onValueChange = { password = it },
                        isPassword = true,
                        keyboardOptions = SettingsVerbatimKeyboard
                    )
                    if (provider == WebDavProvider.TORBOX) {
                        Text(
                            text = stringResource(R.string.webdav_torbox_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                        if (storedTorboxApiKey.isNotBlank() && password != storedTorboxApiKey) {
                            SettingsChoiceChip(
                                label = stringResource(R.string.webdav_use_debrid_key),
                                selected = false,
                                onClick = { password = storedTorboxApiKey }
                            )
                        }
                    }
                }
            }

            ResultBanner(addResult)

            val canSubmit = !busy && baseUrl.isNotBlank() && password.isNotBlank()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                Button(
                    onClick = {
                        viewModel.testConnection(
                            provider = provider,
                            baseUrl = baseUrl,
                            username = username,
                            password = password,
                            rootPath = rootPath
                        )
                    },
                    enabled = canSubmit,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(if (busy) R.string.webdav_working else R.string.webdav_test_connection))
                }
                Button(
                    onClick = {
                        viewModel.addSource(
                            provider = provider,
                            displayName = displayName.ifBlank { providerLabel },
                            baseUrl = baseUrl,
                            username = username,
                            password = password,
                            rootPath = rootPath
                        )
                    },
                    enabled = canSubmit,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.library_test_and_save))
                }
            }
        }
    }
}

@Composable
private fun ResultBanner(result: WebDavSettingsViewModel.AddResult?) {
    when (result) {
        is WebDavSettingsViewModel.AddResult.Failure -> Text(
            text = result.message ?: stringResource(R.string.library_source_add_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.Error
        )

        is WebDavSettingsViewModel.AddResult.Connected -> Text(
            text = quantityStringResource(R.plurals.webdav_connected, result.entryCount),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )

        else -> Unit
    }
}
