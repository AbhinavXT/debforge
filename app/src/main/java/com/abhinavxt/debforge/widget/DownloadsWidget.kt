package com.abhinavxt.debforge.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.domain.QueueSummary
import com.abhinavxt.debforge.download.DownloadController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home-screen widget: what's downloading, a Pause all / Resume all toggle and
 * an Add button. While the app process is alive [WidgetSync] keeps it
 * current; otherwise it's refreshed when placed, resized or tapped.
 */
@AndroidEntryPoint
class DownloadsWidget : AppWidgetProvider() {

    @Inject lateinit var downloadDao: DownloadDao
    @Inject lateinit var controller: DownloadController

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // Hilt injects here; also dispatches onUpdate
        if (intent.action == ACTION_PAUSE_ALL) {
            inBackground {
                controller.pauseAll()
                refresh(context)
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        inBackground { refresh(context) }
    }

    private suspend fun refresh(context: Context) {
        val rows = downloadDao.observeAll().first().map { it.toQueueRow() }
        WidgetRenderer.push(context, QueueSummary.of(rows))
    }

    /** Keeps the process alive until [block] finishes (goAsync allows ~10 s). */
    private fun inBackground(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.abhinavxt.debforge.widget.PAUSE_ALL"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
