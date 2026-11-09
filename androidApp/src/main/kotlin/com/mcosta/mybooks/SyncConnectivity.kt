package com.mcosta.mybooks

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Calls [onRestored] whenever the phone gets a network (again), for as long as the app runs: library sync then sends
 * the changes waiting offline at once, instead of after its retry delay.
 */
internal fun followConnectivity(context: Context, onRestored: () -> Unit) {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
    manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onRestored()
    })
}
