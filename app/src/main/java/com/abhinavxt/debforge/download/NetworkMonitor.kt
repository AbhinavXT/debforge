package com.abhinavxt.debforge.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide internet reachability. Backed by a default-network callback so
 * the engine can suspend until the user is back on a usable connection (not
 * just any network — captive portals where Wi-Fi is "connected" but blocked
 * are reported as unavailable thanks to the NET_CAPABILITY_VALIDATED check).
 *
 * Started eagerly so callers can read `online.value` synchronously without
 * worrying about cold-flow staleness — the cost is one persistent callback
 * registration for the lifetime of the process, which is the right trade for
 * a downloader app.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val online: StateFlow<Boolean> = callbackFlow {
        // Emit current value immediately so collectors don't see the
        // initialValue lag for the first event.
        trySend(currentlyOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentlyOnline())
            }
            override fun onLost(network: Network) {
                // Phone may still have another network (Wi-Fi -> cellular
                // handover); re-evaluate rather than blindly emitting false.
                trySend(currentlyOnline())
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Catches the captive-portal → validated transition.
                trySend(currentlyOnline())
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = currentlyOnline()
        )

    /** Synchronous one-shot check used to seed the StateFlow. */
    private fun currentlyOnline(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
