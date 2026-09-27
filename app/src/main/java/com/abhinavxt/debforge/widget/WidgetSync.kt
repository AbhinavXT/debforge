package com.abhinavxt.debforge.widget

import android.content.Context
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.domain.QueueSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps placed widgets in step with the download queue while the app process
 * is alive (which it is whenever something downloads). Updates are throttled
 * to one every [MIN_INTERVAL_MS]: the engine writes progress many times a
 * second, and every widget update costs the launcher a redraw.
 */
@Singleton
class WidgetSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            downloadDao.observeAll()
                .map { rows -> QueueSummary.of(rows.map { it.toQueueRow() }) }
                .distinctUntilChanged()
                .conflate()
                .collect { summary ->
                    runCatching { WidgetRenderer.push(context, summary) }
                    delay(MIN_INTERVAL_MS)
                }
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 2_000L
    }
}
