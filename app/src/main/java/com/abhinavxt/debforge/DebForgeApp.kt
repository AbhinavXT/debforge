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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Upgrade tokens saved in plaintext by older versions to encrypted
        // storage. No-op once done; failures just leave them readable.
        appScope.launch { runCatching { tokenStore.encryptLegacyValues() } }
    }
}
