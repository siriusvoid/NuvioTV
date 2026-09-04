package com.nuvio.tv.data.subtitles

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nuvio.tv.core.player.SubtitleCharsetDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where imported subtitle files live: one directory per pack under the app's own
 * files directory.
 *
 * Paths are handed around relative to that root rather than absolute. The root
 * moves with the app, so an absolute path recorded today is not guaranteed to
 * still name the same file after a reinstall.
 */
@Singleton
internal class ImportedSubtitleStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val root: File
        get() = File(context.filesDir, ROOT_DIR_NAME)

    /** Copies [source] into [packId]'s directory, returning its path below the root. */
    fun adopt(source: File, packId: String, fileName: String): String? {
        val safeName = sanitize(fileName)
        if (safeName.isBlank()) return null
        return try {
            val packDir = File(root, packId).apply { mkdirs() }
            val target = File(packDir, safeName)
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            "$packId/$safeName"
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy $fileName into pack $packId", e)
            null
        }
    }

    /** A `file://` url for the player, resolved against the root as it is now. */
    fun subtitleUrl(relativePath: String): String = Uri.fromFile(File(root, relativePath)).toString()

    fun exists(relativePath: String): Boolean = File(root, relativePath).isFile

    /**
     * Decoded text of a stored subtitle. The charset detector is the same one the
     * player uses, so an imported file reads the same however it was encoded.
     */
    fun readText(relativePath: String, languageHint: String? = null): String? = try {
        val file = File(root, relativePath)
        if (file.isFile) SubtitleCharsetDetector.decode(file.readBytes(), languageHint = languageHint) else null
    } catch (e: Exception) {
        Log.w(TAG, "Could not read $relativePath", e)
        null
    }

    fun deleteFile(relativePath: String) {
        runCatching { File(root, relativePath).delete() }
    }

    fun deletePack(packId: String) {
        runCatching { File(root, packId).deleteRecursively() }
    }

    /** Strips anything that could climb out of the pack directory. */
    private fun sanitize(fileName: String): String {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        return if (name == "." || name == "..") "" else name
    }

    companion object {
        private const val TAG = "ImportedSubtitleStorage"
        private const val ROOT_DIR_NAME = "imported_subtitles"
    }
}
