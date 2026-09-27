package com.abhinavxt.debforge

import android.app.Application
import com.abhinavxt.debforge.data.prefs.TokenStore
import com.abhinavxt.debforge.diagnostics.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DebForgeApp : Application() {

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var widgetSync: com.abhinavxt.debforge.widget.WidgetSync
    @Inject lateinit var followStore: com.abhinavxt.debforge.data.follow.FollowStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        com.abhinavxt.debforge.download.DownloadFiles.init(this)
        com.abhinavxt.debforge.data.provider.ProviderCalls.init(this)
        // Upgrade tokens saved in plaintext by older versions to encrypted
        // storage. No-op once done; failures just leave them readable.
        appScope.launch { runCatching { tokenStore.encryptLegacyValues() } }
        // Home-screen widget follows the queue while the process is alive.
        widgetSync.start(appScope)
        // Follows restored from a backup (or after "clear data" of the job
        // store): make sure the episode check is scheduled exactly when needed.
        appScope.launch {
            runCatching {
                com.abhinavxt.debforge.download.follow.FollowJobService.sync(this@DebForgeApp, followStore.all().isNotEmpty())
            }
        }
    }
}
