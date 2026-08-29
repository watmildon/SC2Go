package de.westnordost.streetcomplete.data.connection

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_t
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/** Provides information about the default active network connection via the Network framework's
 *  path monitor. */
@OptIn(ExperimentalForeignApi::class)
class IosActiveNetworkConnection : ActiveNetworkConnection {

    /** serial queue the path monitors report on. Must not be the queue of any caller of
     *  [capabilities], as that one is blocked while waiting for the report */
    private val queue = dispatch_queue_create(QUEUE_NAME, null)

    override val capabilitiesFlow: Flow<NetworkCapabilities?> = callbackFlow {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_set_update_handler(monitor) { path -> trySend(path.toNetworkCapabilities()) }
        nw_path_monitor_start(monitor)
        awaitClose { nw_path_monitor_cancel(monitor) }
    }

    /* Contrary to Android, iOS has no API to ask for the current network path, it is only ever
       pushed to us. A path monitor does however report the current path immediately after it has
       been started, so we can start one, take the first thing it tells us and cancel it again. */
    override val capabilities: NetworkCapabilities? get() {
        val result = PathResult()
        val semaphore = dispatch_semaphore_create(0)
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_set_update_handler(monitor) { path ->
            // the monitor keeps reporting until it is cancelled, we are only interested in the first
            if (!result.isSet) {
                result.value = path.toNetworkCapabilities()
                result.isSet = true
                dispatch_semaphore_signal(semaphore)
            }
        }
        nw_path_monitor_start(monitor)
        dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW, TIMEOUT_IN_NANOS))
        nw_path_monitor_cancel(monitor)
        return result.value
    }
}

private class PathResult {
    var value: NetworkCapabilities? = null
    var isSet: Boolean = false
}

/* Contrary to Android, iOS does not tell us whether it validated that the network actually reaches
   the internet, so a satisfied path is the best approximation of `hasInternet` we have.
   A path that is not satisfied - or not known yet - is reported as no connection at all, same as
   what Android reports when there is no active network. */
@OptIn(ExperimentalForeignApi::class)
private fun nw_path_t.toNetworkCapabilities(): NetworkCapabilities? {
    if (this == null) return null
    if (nw_path_get_status(this) != nw_path_status_satisfied) return null
    return NetworkCapabilities(
        hasInternet = true,
        // "expensive" is cellular or personal hotspot, "constrained" is iOS' Low Data Mode
        isMetered = nw_path_is_expensive(this) || nw_path_is_constrained(this)
    )
}

private const val QUEUE_NAME = "de.westnordost.streetcomplete.network-path-monitor"

/** the current path is reported practically immediately, this is just to never block forever */
private const val TIMEOUT_IN_NANOS = 1_000_000_000L
