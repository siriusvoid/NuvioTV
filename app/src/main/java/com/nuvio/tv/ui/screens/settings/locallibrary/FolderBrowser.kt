@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import android.content.Context
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.screens.settings.settingsOptionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.quantityStringResource
import java.io.File

/** Frames to wait for a focus target to attach before giving up on it. */
private const val FOCUS_ATTEMPTS = 8

/** D-pad folder picker standing in for SAF on TV; needs the media-read permission already granted. */
@Composable
fun FolderBrowser(
    onSelect: (File) -> Unit,
    onCancel: () -> Unit,
    /** What counts as an interesting file, reported in the header as a count. */
    fileMatcher: (String) -> Boolean = ::isVideoFileName,
    @PluralsRes filesHere: Int = R.plurals.folder_browser_video_files
) {
    val context = LocalContext.current
    val roots = remember { storageRoots(context) }
    // null = showing the list of storage roots; otherwise the directory we're in.
    var current by remember { mutableStateOf(if (roots.size == 1) roots.first() else null) }

    val subDirs: List<File> = remember(current) {
        val dir = current
        if (dir == null) roots
        else (dir.listFiles()?.asList() ?: emptyList())
            .filter { it.isDirectory && !it.isHidden }
            .sortedBy { it.name.lowercase() }
    }
    val fileCount: Int = remember(current) {
        current?.listFiles()?.count { it.isFile && fileMatcher(it.name) } ?: 0
    }

    // At the drive list, or at the only storage root, there's nowhere up to go.
    val atTopLevel = current == null || (current in roots && roots.size <= 1)

    fun goUp() {
        val dir = current ?: return
        current = when {
            roots.size > 1 && dir in roots -> null
            else -> dir.parentFile ?: if (roots.size > 1) null else dir
        }
    }

    BackHandler { if (atTopLevel) onCancel() else goUp() }

    val listState = rememberLazyListState()
    val firstItemFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    // Moving between folders focuses the list (Use this folder when empty), retrying while rows attach.
    LaunchedEffect(current, subDirs.size) {
        val primary = if (subDirs.isEmpty()) actionFocus else firstItemFocus
        val fallback = if (subDirs.isEmpty()) firstItemFocus else actionFocus
        repeat(FOCUS_ATTEMPTS) {
            if (runCatching { primary.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
        runCatching { fallback.requestFocus() }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        val subfolders = quantityStringResource(R.plurals.folder_browser_subfolders, subDirs.size)
        SettingsDetailHeader(
            title = current?.let { it.name.ifBlank { it.absolutePath } }
                ?: stringResource(R.string.folder_browser_select_storage),
            subtitle = when {
                current == null -> stringResource(R.string.folder_browser_choose_drive)
                fileCount > 0 -> "${current?.absolutePath} · " +
                    "${quantityStringResource(filesHere, fileCount)} · $subfolders"
                else -> "${current?.absolutePath} · $subfolders"
            }
        )

        // Actions above the list; settingsOptionRow makes Use this folder the way in, not Cancel.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .settingsOptionRow(actionFocus),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Button(
                onClick = { current?.let(onSelect) },
                enabled = current != null,
                modifier = Modifier.focusRequester(actionFocus),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(
                    text = current?.let {
                        stringResource(R.string.folder_browser_use_named, it.name.ifBlank { it.absolutePath })
                    } ?: stringResource(R.string.folder_browser_use_this)
                )
            }
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.library_folders)
        ) {
            if (subDirs.isEmpty() && current != null) {
                Text(
                    text = stringResource(R.string.folder_browser_no_subfolders),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                    ) {
                        // No up row: back already walks out of a folder, then out of the browser.
                        itemsIndexed(subDirs) { index, dir ->
                            SettingsActionRow(
                                title = dir.name.ifBlank { dir.absolutePath },
                                subtitle = null,
                                leadingIcon = Icons.Default.Folder,
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstItemFocus)
                                } else {
                                    Modifier
                                },
                                onClick = { current = dir }
                            )
                        }
                    }
                    SettingsVerticalScrollIndicators(state = listState)
                }
            }
        }
    }
}

/** Internal storage plus removable volumes, found by walking up from each volume's app files dir. */
internal fun storageRoots(context: Context): List<File> {
    val roots = LinkedHashSet<File>()
    runCatching { Environment.getExternalStorageDirectory() }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?.let { roots += it }
    runCatching { context.getExternalFilesDirs(null) }.getOrNull()?.forEach { f ->
        var volume: File? = f ?: return@forEach
        // …/Android/data/<pkg>/files  ->  volume root is 4 levels up.
        repeat(4) { volume = volume?.parentFile }
        volume?.takeIf { it.isDirectory }?.let { roots += it }
    }
    return roots.toList()
}

private val VIDEO_EXTS = setOf(
    "mp4", "mkv", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg", "m4v"
)

internal fun isVideoFileName(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot < 0) return false
    return name.substring(dot + 1).lowercase() in VIDEO_EXTS
}
