package com.abhinavxt.debforge.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Where downloads may go. Neither option needs a permission:
 *  - A folder picked with the system folder picker (internal storage, SD
 *    card, USB): access granted by the user's pick.
 *  - The app's own external folder (Android/data/<pkg>/files): files there
 *    are removed if the app is uninstalled. The fallback for devices
 *    without a folder picker (some Android TVs).
 *
 * Shared folders by path (Movies/DebForge, the old default) needed "All
 * files access", which DebForge no longer asks for. Such a setting counts
 * as not writable, so the user is asked to choose a folder again.
 */
object StorageAccess {

    fun appStorageDir(context: Context): String =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "DebForge").absolutePath

    fun isAppStorage(context: Context, dir: String): Boolean {
        val root = context.getExternalFilesDir(null)?.absolutePath ?: return false
        return dir.startsWith(root) || dir.startsWith(context.filesDir.absolutePath)
    }

    /** True if saving to [dir] can work right now. False while no folder is chosen. */
    fun canWrite(context: Context, dir: String): Boolean = when {
        dir.isBlank() -> false
        SafPaths.isTreeUri(dir) -> SafStore.hasAccess(context, dir)
        else -> isAppStorage(context, dir)
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

    /**
     * What to show for a download folder: the path, "SD card (…)/Movies" for
     * a picked folder, or "No folder chosen".
     */
    fun label(context: Context, dir: String): String =
        if (dir.isBlank()) {
            context.getString(com.abhinavxt.debforge.R.string.storage_not_chosen)
        } else if (SafPaths.isTreeUri(dir)) {
            SafPaths.displayName(
                dir,
                internalLabel = context.getString(com.abhinavxt.debforge.R.string.storage_internal),
                sdLabel = context.getString(com.abhinavxt.debforge.R.string.storage_sd)
            )
        } else dir
}
