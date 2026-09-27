package com.abhinavxt.debforge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.view.View
import android.widget.RemoteViews
import com.abhinavxt.debforge.AppActions
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.local.DownloadEntity
import com.abhinavxt.debforge.domain.QueueRow
import com.abhinavxt.debforge.domain.QueueSummary

fun DownloadEntity.toQueueRow() = QueueRow(
    name = filename,
    state = state,
    bytesDone = bytesDownloaded,
    totalBytes = filesize,
    createdAt = createdAt
)

/** Builds the widget's RemoteViews from a [QueueSummary] and pushes them. */
object WidgetRenderer {

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, DownloadsWidget::class.java))

    /** Updates every placed widget. Cheap no-op when none is placed. */
    fun push(context: Context, summary: QueueSummary) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        AppWidgetManager.getInstance(context).updateAppWidget(ids, render(context, summary))
    }

    fun render(context: Context, s: QueueSummary): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_downloads)
        val res = context.resources

        v.setTextViewText(R.id.widget_counts, countsLine(context, s))

        val current = s.current
        if (current == null) {
            v.setTextViewText(R.id.widget_name, context.getString(R.string.widget_idle))
            v.setTextViewText(R.id.widget_detail, context.getString(R.string.widget_idle_hint))
            v.setViewVisibility(R.id.widget_progress, View.GONE)
        } else {
            v.setTextViewText(R.id.widget_name, current.name)
            val percent = s.currentPercent
            v.setViewVisibility(R.id.widget_progress, View.VISIBLE)
            v.setProgressBar(R.id.widget_progress, 100, percent ?: 0, percent == null)
            val done = Formatter.formatShortFileSize(context, current.bytesDone)
            v.setTextViewText(
                R.id.widget_detail,
                if (current.totalBytes > 0) {
                    context.getString(
                        R.string.widget_progress,
                        done, Formatter.formatShortFileSize(context, current.totalBytes), percent ?: 0
                    )
                } else done
            )
        }

        // One toggle: pause when something runs or waits, resume when paused.
        when {
            s.hasActive -> {
                v.setViewVisibility(R.id.widget_toggle, View.VISIBLE)
                v.setImageViewResource(R.id.widget_toggle_icon, R.drawable.ic_action_pause)
                v.setTextViewText(R.id.widget_toggle_text, res.getString(R.string.action_pause_all))
                v.setOnClickPendingIntent(R.id.widget_toggle, pauseAllIntent(context))
            }
            s.paused > 0 -> {
                v.setViewVisibility(R.id.widget_toggle, View.VISIBLE)
                v.setImageViewResource(R.id.widget_toggle_icon, R.drawable.ic_action_play)
                v.setTextViewText(R.id.widget_toggle_text, res.getString(R.string.action_resume_all))
                // Opens the app: Android only allows starting downloads from the foreground.
                v.setOnClickPendingIntent(R.id.widget_toggle, AppActions.pending(context, AppActions.RESUME_ALL))
            }
            else -> v.setViewVisibility(R.id.widget_toggle, View.GONE)
        }
        v.setOnClickPendingIntent(R.id.widget_add, AppActions.pending(context, AppActions.ADD))
        v.setOnClickPendingIntent(R.id.widget_root, AppActions.pending(context, null))
        return v
    }

    /** "2 downloading · 3 queued · 1 paused", counts that are 0 left out. */
    fun countsLine(context: Context, s: QueueSummary): String {
        val r = context.resources
        return listOfNotNull(
            s.running.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.widget_n_downloading, it, it) },
            s.queued.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.widget_n_queued, it, it) },
            s.paused.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.widget_n_paused, it, it) },
            s.failed.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.widget_n_failed, it, it) }
        ).joinToString(" · ")
    }

    private fun pauseAllIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DownloadsWidget::class.java).setAction(DownloadsWidget.ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
