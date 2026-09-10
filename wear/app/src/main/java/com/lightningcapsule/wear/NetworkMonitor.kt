package com.lightningcapsule.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Invokes [onAvailable] whenever the default network becomes usable — the cue to
 * drain the offline queue. Register in `onStart`, unregister in `onStop`.
 *
 * [onAvailable] is delivered on a framework binder thread; callers must hop back
 * to the main thread before touching UI state.
 */
class NetworkMonitor(
    context: Context,
    private val onAvailable: () -> Unit,
) {

    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }

    private var registered = false

    fun start() {
        if (registered || connectivity == null) return
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
    }

    fun stop() {
        if (!registered || connectivity == null) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
    }
}
