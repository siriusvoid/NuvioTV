@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import android.content.Context
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors
import java.io.File

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
    onCancel: () -> Unit
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
    val videoCount: Int = remember(current) {
        current?.listFiles()?.count { it.isFile && isVideoFileName(it.name) } ?: 0
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
    // Inside a folder, land on "Use this folder" so the current folder is always
    // selectable in one press, even when it has many subfolders. On the drive
    // list, land on the first drive instead.
    LaunchedEffect(current) {
        runCatching {
            if (current != null) actionFocus.requestFocus() else firstItemFocus.requestFocus()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = current?.absolutePath ?: "Select storage",
            color = NuvioColors.TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = when {
                current == null -> "Choose a drive, then open folders and press \"Use this folder\""
                videoCount > 0 -> "$videoCount video file(s) here · ${subDirs.size} subfolder(s)"
                else -> "${subDirs.size} subfolder(s)"
            },
            color = NuvioColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )

        // Actions sit above the list so the open folder can always be selected
        // without scrolling past all of its subfolders.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { current?.let(onSelect) },
                enabled = current != null,
                modifier = Modifier.focusRequester(actionFocus),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.FocusRing,
                    contentColor = Color.Black,
                    focusedContainerColor = NuvioColors.FocusRing,
                    focusedContentColor = Color.Black
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.TextPrimary),
                        shape = RoundedCornerShape(50)
                    )
                )
            ) {
                Text(
                    text = current?.let { "Use \"${it.name.ifBlank { it.absolutePath }}\"" } ?: "Use this folder",
                    color = Color.Black
                )
            }

            Button(
                onClick = onCancel,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Background,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContainerColor = NuvioColors.Background,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(50)
                    )
                )
            ) { Text("Cancel", color = NuvioColors.TextPrimary) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // First row is "up", except at the top level (drive list / sole root).
            if (!atTopLevel) {
                item {
                    FolderRow(
                        label = "⬆  Up one level",
                        modifier = Modifier.focusRequester(firstItemFocus),
                        onClick = { goUp() }
                    )
                }
            }
            itemsIndexed(subDirs) { index, dir ->
                val mod = if (atTopLevel && index == 0) {
                    Modifier.focusRequester(firstItemFocus)
                } else {
                    Modifier
                }
                FolderRow(
                    label = "📁  ${dir.name}",
                    modifier = mod,
                    onClick = { current = dir }
                )
            }
            if (subDirs.isEmpty() && current != null) {
                item {
                    Text(
                        text = "No subfolders — press \"Use this folder\" above to pick this one.",
                        color = NuvioColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = NuvioColors.Background,
            contentColor = NuvioColors.TextPrimary,
            focusedContainerColor = NuvioColors.BackgroundElevated,
            focusedContentColor = NuvioColors.TextPrimary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        Text(
            text = label,
            color = NuvioColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        )
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

private fun isVideoFileName(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot < 0) return false
    return name.substring(dot + 1).lowercase() in VIDEO_EXTS
}
