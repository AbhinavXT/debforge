package com.abhinavxt.debforge.download

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.local.DownloadEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Downloaded" notification with Open/Play and Share, plus the helpers the
 * Downloads screen uses for the same actions on finished files.
 */
@Singleton
class CompletionNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun notifyCompleted(entity: DownloadEntity) {
        val path = entity.finalFilePath ?: return
        if (!LocalFiles.exists(path)) return
        DownloadNotifications.ensureChannel(context)

        val requestBase = entity.id.hashCode()
        val openIntent = LocalFiles.openIntent(context, path) ?: return
        val shareIntent = LocalFiles.shareIntent(context, path) ?: return
        val open = PendingIntent.getActivity(
            context, requestBase, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val share = PendingIntent.getActivity(
            context, requestBase + 1, shareIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val isVideo = LocalFiles.mimeType(LocalFiles.name(path)).startsWith("video/")
        val n = NotificationCompat.Builder(context, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(context.getString(R.string.notif_downloaded))
            .setContentText(entity.filename)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(if (isVideo) R.string.action_play else R.string.action_open), open)
            .addAction(0, context.getString(R.string.action_share), share)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFY_BASE + (requestBase and 0xFFFF), n)
        }
    }

    private companion object {
        const val NOTIFY_BASE = 20_000
    }
}

/**
 * Content-URI helpers for finished files: FileProvider for plain paths (see
 * file_paths.xml), the document URI for files in a picked folder ([SafPaths]).
 * Some calls touch storage; call them off the main thread where possible.
 */
object LocalFiles {

    fun exists(path: String): Boolean = DownloadFiles.exists(path)

    /** File name of [path], either kind. */
    fun name(path: String): String = SafPaths.parse(path)?.name ?: File(path).name

    /** Folder to show under a finished file: "/storage/…/Movies" or "SD card (…)/Movies/Shows". */
    fun folderLabel(context: Context, path: String): String {
        val saf = SafPaths.parse(path) ?: return File(path).parent.orEmpty()
        return (listOf(StorageAccess.label(context, saf.treeUri)) + saf.dirs).joinToString("/")
    }

    fun uriFor(context: Context, path: String): Uri? =
        if (SafPaths.isSaf(path)) SafStore.find(context, path)
        else runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", File(path)) }.getOrNull()

    fun mimeType(fileName: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase()) ?: "*/*"

    /** Opens in the matching app (video player for videos). Chooser so users can pick. */
    fun openIntent(context: Context, path: String): Intent? {
        val uri = uriFor(context, path) ?: return null
        val name = name(path)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(name))
            .putExtra("title", name.substringBeforeLast('.'))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(view, context.getString(R.string.chooser_open_with))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(context: Context, path: String): Intent? {
        val uri = uriFor(context, path) ?: return null
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType(name(path)))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, context.getString(R.string.chooser_share))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
