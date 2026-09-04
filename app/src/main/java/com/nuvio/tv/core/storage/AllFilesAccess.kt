package com.nuvio.tv.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Needed to read .srt/.ass off shared storage: READ_MEDIA_VIDEO covers only video, and TV has no picker. */
object AllFilesAccess {

    /** Whether this Android version gates non-media reads behind the grant. */
    val isRequired: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    // Spelled out rather than via isRequired so lint sees the API 30 guard.
    fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Grant screens, best first. Not filtered by resolveActivity: package visibility can hide ones that exist. */
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
