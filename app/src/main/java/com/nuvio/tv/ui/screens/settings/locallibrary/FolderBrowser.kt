@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import android.content.Context
import android.os.Environment
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.screens.settings.settingsOptionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File

/** Frames to wait for a focus target to attach before giving up on it. */
private const val FOCUS_ATTEMPTS = 8

/**
 * D-pad navigable folder picker used in place of the Storage Access Framework
 * document picker, which is absent on Android TV / Google TV.
 *
 * Backed directly by [java.io.File], so it requires the `READ_MEDIA_VIDEO`
 * (API 33+) / `READ_EXTERNAL_STORAGE` runtime permission to already be granted.
 * Invokes [onSelect] with the folder the user confirms, or [onCancel] to abort.
 */
@Composable
fun FolderBrowser(
    onSelect: (File) -> Unit,
    onCancel: () -> Unit,
    /** What counts as an interesting file, reported in the header as a count. */
    fileMatcher: (String) -> Boolean = ::isVideoFileName,
    fileNoun: String = "video file"
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
    // Moving between folders — in or back out — lands in the list, so browsing
    // is one straight run of presses. An empty folder has no rows to hold focus,
    // so that one lands on "Use this folder" instead.
    //
    // A requestFocus() aimed at a row the LazyColumn hasn't attached yet throws,
    // so retry over a few frames rather than swallowing it and letting focus go
    // wherever the system picks.
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
        SettingsDetailHeader(
            title = current?.let { it.name.ifBlank { it.absolutePath } } ?: "Select storage",
            subtitle = when {
                current == null -> "Choose a drive, then open folders and press \"Use this folder\""
                fileCount > 0 -> "${current?.absolutePath} · $fileCount $fileNoun(s) here · " +
                    "${subDirs.size} subfolder(s)"
                else -> "${current?.absolutePath} · ${subDirs.size} subfolder(s)"
            }
        )

        // Actions sit above the list so the open folder can always be selected
        // without scrolling past all of its subfolders. settingsOptionRow names
        // "Use this folder" as the way in — without it, coming up from the
        // full-width rows below runs a geometric search that picks Cancel.
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
                Text(text = current?.let { "Use \"${it.name.ifBlank { it.absolutePath }}\"" } ?: "Use this folder")
            }
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text("Cancel")
            }
        }

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = "Folders"
        ) {
            if (subDirs.isEmpty() && current != null) {
                Text(
                    text = "No subfolders — press \"Use this folder\" above to pick this one.",
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
                        // No "up" row: back already walks out of a folder, and out
                        // of the browser once there is nowhere left to go.
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

/**
 * Discovers browsable storage roots: primary internal storage plus any
 * removable volumes (USB / SD), derived from the app's per-volume external
 * dirs by walking up out of `Android/data/<pkg>/files`.
 */
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
