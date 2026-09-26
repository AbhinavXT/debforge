package com.abhinavxt.debforge.download

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Where downloads may go and what permission that needs.
 *
 * Shared folders (Movies/, Download/) need "All files access". The app's own
 * external folder (Android/data/<pkg>/files) needs no permission at all —
 * the fallback for devices without that settings screen (many Android TVs)
 * or users who'd rather not grant it. Note: files there are removed if the
 * app is uninstalled.
 */
object StorageAccess {

    fun appStorageDir(context: Context): String =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "DebForge").absolutePath

    fun isAppStorage(context: Context, dir: String): Boolean {
        val root = context.getExternalFilesDir(null)?.absolutePath ?: return false
        return dir.startsWith(root) || dir.startsWith(context.filesDir.absolutePath)
    }

    /** True if saving to [dir] can work right now. */
    fun canWrite(context: Context, dir: String): Boolean =
        isAppStorage(context, dir) || Environment.isExternalStorageManager()

    /**
     * Opens the "All files access" screen. Returns false where it doesn't
     * exist (common on Android TV) so the caller can offer app storage.
     */
    fun requestAllFiles(context: Context): Boolean {
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOf(specific, generic)) {
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                // try the next one
            }
        }
        return false
    }
}
