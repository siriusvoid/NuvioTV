package com.nuvio.tv.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * "All files access" — what it takes to read a subtitle file off shared storage.
 *
 * Android 11 retired the blanket storage permission, and from Android 13 this app
 * holds `READ_MEDIA_VIDEO` alone, which covers video files and nothing else. A
 * `.srt` or `.ass` is not a media file, so without this grant a folder full of
 * them cannot even be stat'ed and reads as empty. There is no document picker to
 * fall back on: Android TV ships none.
 */
object AllFilesAccess {

    /** Whether this Android version gates non-media reads behind the grant. */
    val isRequired: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    // The version check is spelled out rather than deferred to isRequired so that
    // lint can see it guarding an API 30 call.
    fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * System screens that grant it, best first. Android TV builds often carry
     * neither, which is what [adbCommand] is for.
     *
     * Not filtered through `resolveActivity`: package-visibility rules can hide
     * Settings from that query even where the screen exists, so the caller tries
     * each in turn and treats a failure to launch as "this one is missing".
     */
    fun grantIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null)
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
    }

    /** The way in on a device whose Settings has no such screen. */
    fun adbCommand(context: Context): String =
        "adb shell appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
}
