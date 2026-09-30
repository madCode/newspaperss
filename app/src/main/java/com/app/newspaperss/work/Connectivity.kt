package com.app.newspaperss.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

object Connectivity {
    /**
     * Whether the phone has a working internet connection: now, and each time that changes.
     * "Working" is validated, as WorkManager's connection constraint is: a captive portal's Wi-Fi
     * counts as offline here, or Today would say "checking" while the work waits.
     */
    fun online(context: Context): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun validated(caps: NetworkCapabilities?) =
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(validated(caps))
            }

            override fun onLost(network: Network) {
                trySend(validated(manager.getNetworkCapabilities(manager.activeNetwork)))
            }
        }
        trySend(validated(manager.getNetworkCapabilities(manager.activeNetwork)))
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            // Some Android 11 builds throw here (as WorkManager also guards against). Knowing the
            // connection only improves a status line, so assume it's there.
            trySend(true)
            close()
            return@callbackFlow
        } catch (e: IllegalArgumentException) {
            trySend(true)
            close()
            return@callbackFlow
        }
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
        // Going offline counts only once it lasts: a flaky connection would otherwise flip the
        // status line (and TalkBack, and an e-ink refresh) back and forth.
        .transformLatest { online ->
            if (!online) delay(OFFLINE_AFTER_MS)
            emit(online)
        }
        .distinctUntilChanged()

    private const val OFFLINE_AFTER_MS = 3_000L
}
