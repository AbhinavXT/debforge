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
 * Three options, easiest first:
 *  - A folder picked with the system folder picker (internal storage, SD
 *    card, USB): no special permission, access granted by the user's pick.
 *  - Shared folders (Movies/, Download/) by path: need "All files access".
 *  - The app's own external folder (Android/data/<pkg>/files): no
 *    permission at all, but files there are removed if the app is
 *    uninstalled. The fallback for devices without a folder picker or the
 *    All-files settings screen (some Android TVs).
 */
object StorageAccess {

    fun appStorageDir(context: Context): String =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "DebForge").absolutePath

    fun isAppStorage(context: Context, dir: String): Boolean {
        val root = context.getExternalFilesDir(null)?.absolutePath ?: return false
        return dir.startsWith(root) || dir.startsWith(context.filesDir.absolutePath)
    }

    /** True if saving to [dir] can work right now. */
    fun canWrite(context: Context, dir: String): Boolean = when {
        SafPaths.isTreeUri(dir) -> SafStore.hasAccess(context, dir)
        else -> isAppStorage(context, dir) || Environment.isExternalStorageManager()
    }

    /**
     * Keeps access to a folder chosen with the system folder picker across
     * restarts. No special permission is involved.
     *
     * Grants for previously chosen folders are deliberately KEPT: queued and
     * finished downloads still point into them (resuming, opening, sharing),
     * and Android allows hundreds of persisted grants per app.
     */
    @Suppress("UNUSED_PARAMETER")
    fun rememberPickedFolder(context: Context, tree: Uri, previous: String?) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
    }

    /** What to show for a download folder: the path, or "SD card (…)/Movies" for a picked folder. */
    fun label(context: Context, dir: String): String =
        if (SafPaths.isTreeUri(dir)) {
            SafPaths.displayName(
                dir,
                internalLabel = context.getString(com.abhinavxt.debforge.R.string.storage_internal),
                sdLabel = context.getString(com.abhinavxt.debforge.R.string.storage_sd)
            )
        } else dir

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
