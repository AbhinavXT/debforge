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
        val file = File(path)
        if (!file.exists()) return
        DownloadNotifications.ensureChannel(context)

        val requestBase = entity.id.hashCode()
        val open = PendingIntent.getActivity(
            context, requestBase, LocalFiles.openIntent(context, file),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val share = PendingIntent.getActivity(
            context, requestBase + 1, LocalFiles.shareIntent(context, file),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val isVideo = LocalFiles.mimeType(file).startsWith("video/")
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

/** Content-URI helpers for finished files (FileProvider, see file_paths.xml). */
object LocalFiles {

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    /** Opens in the matching app (video player for videos). Chooser so users can pick. */
    fun openIntent(context: Context, file: File): Intent {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeType(file))
            .putExtra("title", file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(view, context.getString(R.string.chooser_open_with))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(context: Context, file: File): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType(file))
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, context.getString(R.string.chooser_share))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
