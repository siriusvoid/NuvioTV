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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
    // A message left over from the previous visit would read as a verdict on a form
    // the user has not filled in yet.
    LaunchedEffect(Unit) { viewModel.clearAddResult() }

    var providerId by rememberSaveable { mutableStateOf(WebDavProvider.REAL_DEBRID.id) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf(WebDavProvider.REAL_DEBRID.defaultBaseUrl) }
    var rootPath by rememberSaveable { mutableStateOf(WebDavProvider.REAL_DEBRID.defaultRootPath) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val provider = WebDavProvider.fromId(providerId)
    val firstProviderChip = remember { FocusRequester() }

    fun applyProvider(next: WebDavProvider) {
        providerId = next.id
        baseUrl = next.defaultBaseUrl
        rootPath = next.defaultRootPath
        username = next.fixedUsername.orEmpty()
        viewModel.clearAddResult()
    }

    SettingsStandaloneScaffold(
        title = "Add WebDAV source",
        subtitle = "Point Nuvio at a debrid WebDAV share."
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            SettingsDetailHeader(
                title = "New source",
                subtitle = "Pick a provider to fill in its address, then enter your credentials."
            )

            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Provider"
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsOptionRow(firstProviderChip),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    WebDavProvider.entries.forEachIndexed { index, option ->
                        SettingsToggleChip(
                            label = option.displayName,
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
                title = "Connection"
            ) {
                // One column owns every gap in this card: the group card spaces its
                // own children tightly, which leaves form fields crowded together.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    SettingsTextRow(
                        label = "Display name",
                        value = displayName,
                        onValueChange = { displayName = it },
                        placeholder = provider.displayName
                    )
                    SettingsTextRow(
                        label = "Server address",
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        keyboardOptions = SettingsVerbatimKeyboard
                    )
                    SettingsTextRow(
                        label = "Folder path",
                        value = rootPath,
                        onValueChange = { rootPath = it },
                        placeholder = "Server root",
                        keyboardOptions = SettingsVerbatimKeyboard
                    )
                    SettingsTextRow(
                        label = "Username",
                        value = username,
                        onValueChange = { username = it },
                        enabled = provider.fixedUsername == null,
                        keyboardOptions = SettingsVerbatimKeyboard
                    )
                    SettingsTextRow(
                        label = if (provider == WebDavProvider.TORBOX) "API key" else "Password",
                        value = password,
                        onValueChange = { password = it },
                        isPassword = true,
                        keyboardOptions = SettingsVerbatimKeyboard
                    )
                    if (provider == WebDavProvider.TORBOX) {
                        Text(
                            text = "TorBox signs in with your API key as the password, " +
                                "with a fixed username.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                        if (storedTorboxApiKey.isNotBlank() && password != storedTorboxApiKey) {
                            SettingsChoiceChip(
                                label = "Use the key from Debrid settings",
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
                    Text(if (busy) "Working…" else "Test connection")
                }
                Button(
                    onClick = {
                        viewModel.addSource(
                            provider = provider,
                            displayName = displayName,
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
                    Text("Test & save")
                }
            }
        }
    }
}

@Composable
private fun ResultBanner(result: WebDavSettingsViewModel.AddResult?) {
    when (result) {
        is WebDavSettingsViewModel.AddResult.Failure -> Text(
            text = result.message,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.Error
        )

        is WebDavSettingsViewModel.AddResult.Message -> Text(
            text = result.text,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )

        else -> Unit
    }
}
