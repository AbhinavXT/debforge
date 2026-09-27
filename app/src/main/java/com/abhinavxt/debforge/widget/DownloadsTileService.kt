package com.abhinavxt.debforge.widget

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.abhinavxt.debforge.AppActions
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.local.DownloadDao
import com.abhinavxt.debforge.domain.QueueSummary
import com.abhinavxt.debforge.download.DownloadController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile. On (active) while something downloads or waits; a tap
 * pauses everything. Off with paused downloads: a tap opens DebForge and
 * resumes them (starting downloads needs the app in front). Off with nothing
 * to do: a tap opens the Add dialog.
 */
@AndroidEntryPoint
class DownloadsTileService : TileService() {

    @Inject lateinit var downloadDao: DownloadDao
    @Inject lateinit var controller: DownloadController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var summary = QueueSummary.EMPTY

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        // Live while the shade is open: the tile flips as downloads finish.
        listening = scope.launch {
            downloadDao.observeAll()
                .map { rows -> QueueSummary.of(rows.map { it.toQueueRow() }) }
                // Counts only: byte progress would redraw the tile constantly.
                .distinctUntilChanged { a, b ->
                    a.running == b.running && a.queued == b.queued && a.paused == b.paused
                }
                .collect { s ->
                    summary = s
                    render(s)
                }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val s = summary
        when {
            s.hasActive -> scope.launch { controller.pauseAll() }
            s.paused > 0 -> openApp(AppActions.RESUME_ALL)
            else -> openApp(AppActions.ADD)
        }
    }

    // Below Android 14 the Intent overload is the only one there is.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(action: String) {
        val go = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(AppActions.pending(this, action))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(AppActions.intent(this, action))
            }
        }
        if (isLocked) unlockAndRun { go() } else go()
    }

    private fun render(s: QueueSummary) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.state = if (s.hasActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            s.hasActive -> WidgetRenderer.countsLine(this, s.copy(paused = 0, failed = 0))
            s.paused > 0 -> resources.getQuantityString(R.plurals.widget_n_paused, s.paused, s.paused)
            else -> getString(R.string.tile_idle)
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
