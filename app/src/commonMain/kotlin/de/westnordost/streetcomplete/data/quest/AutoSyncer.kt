package de.westnordost.streetcomplete.data.quest

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.westnordost.streetcomplete.data.UnsyncedChangesCountSource
import de.westnordost.streetcomplete.data.connection.ActiveNetworkConnection
import de.westnordost.streetcomplete.data.download.DownloadController
import de.westnordost.streetcomplete.data.download.DownloadProgressSource
import de.westnordost.streetcomplete.data.download.strategy.MobileDataAutoDownloadStrategy
import de.westnordost.streetcomplete.data.download.strategy.WifiAutoDownloadStrategy
import de.westnordost.streetcomplete.data.download.tiles.DownloadedTilesController
import de.westnordost.streetcomplete.data.location.LocationUpdatesSource
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.preferences.Autosync
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.data.upload.UploadController
import de.westnordost.streetcomplete.data.user.UserLoginSource
import de.westnordost.streetcomplete.data.visiblequests.TeamModeQuestFilterSource
import de.westnordost.streetcomplete.util.ktx.format
import de.westnordost.streetcomplete.util.logs.Log
import de.westnordost.streetcomplete.util.math.distanceTo
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.maplibre.compose.location.LocationEvent
import org.maplibre.spatialk.units.extensions.meters

/** Automatically downloads map data around the user's location and uploads edits.
 *
 * Respects the user preference to only sync on wifi or not sync automatically at all
 */
class AutoSyncer(
    private val downloadController: DownloadController,
    private val uploadController: UploadController,
    private val mobileDataDownloadStrategy: MobileDataAutoDownloadStrategy,
    private val wifiDownloadStrategy: WifiAutoDownloadStrategy,
    private val locationUpdatesSource: LocationUpdatesSource,
    private val activeNetworkConnection: ActiveNetworkConnection,
    private val unsyncedChangesCountSource: UnsyncedChangesCountSource,
    private val downloadProgressSource: DownloadProgressSource,
    private val userLoginSource: UserLoginSource,
    private val prefs: Preferences,
    private val teamModeQuestFilterSource: TeamModeQuestFilterSource,
    private val downloadedTilesController: DownloadedTilesController
) : DefaultLifecycleObserver {

    private val coroutineScope = CoroutineScope(SupervisorJob() + CoroutineName("QuestAutoSyncer"))

    private var pos: LatLon? = null

    // there are unsynced changes -> try uploading now
    private val unsyncedChangesListener = object : UnsyncedChangesCountSource.Listener {
        override fun onIncreased() { triggerAutoUpload() }
        override fun onDecreased() {}
    }

    // on download finished, should recheck conditions for download
    private val downloadProgressListener = object : DownloadProgressSource.Listener {
        override fun onSuccess() {
            triggerAutoDownload()
        }
    }

    private val userLoginStatusListener = object : UserLoginSource.Listener {
        override fun onLoggedIn() {
            triggerAutoUpload()
        }

        override fun onLoggedOut() {}
    }

    private val teamModeChangeListener = object : TeamModeQuestFilterSource.Listener {
        override fun onTeamModeChanged(enabled: Boolean) {
            if (!enabled) {
                // because other team members will have solved some of the quests already
                downloadedTilesController.invalidateAll()
                triggerAutoDownload()
            }
        }
    }

    val isAllowedByPreference: Boolean get() = when (prefs.autosync) {
        Autosync.ON -> true
        Autosync.WIFI -> activeNetworkConnection.capabilities?.isMetered == false
        Autosync.OFF -> false
    }

    /* ---------------------------------------- Lifecycle --------------------------------------- */

    override fun onCreate(owner: LifecycleOwner) {
        unsyncedChangesCountSource.addListener(unsyncedChangesListener)
        downloadProgressSource.addListener(downloadProgressListener)
        userLoginSource.addListener(userLoginStatusListener)
        teamModeQuestFilterSource.addListener(teamModeChangeListener)

        coroutineScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activeNetworkConnection.capabilitiesFlow.collect { capabilities ->
                    if (capabilities?.hasInternet == true) {
                        triggerAutoSync()
                    }
                }
            }
        }
        coroutineScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                /* The shared stream rather than a request of its own: on iOS each collection of
                   updates() is its own CLLocationManager, and the process runs at the most
                   aggressive of them - so a request for High here would pin the GPS at
                   kCLLocationAccuracyBest no matter what the main screen asks for. Any fix under
                   300 m is accepted below, so the stream's accuracy is always enough.

                   The stream delivers a fix every metre or so, where this used to ask for one
                   every 100 m, and a download check is not free: it probes the network, logs to
                   the database and queries the downloaded tiles and element counts. So the 100 m
                   filter is applied here instead, against the position of the last check. */
                locationUpdatesSource.updates.collect { locationEvent ->
                    if (locationEvent !is LocationEvent.Fix) return@collect
                    val (position, accuracy) = locationEvent.location.position
                    if (accuracy != null && accuracy >= 300.meters) return@collect
                    val newPos = LatLon(position.latitude, position.longitude)
                    val lastPos = pos
                    if (lastPos != null && lastPos.distanceTo(newPos) < MIN_DISTANCE_BETWEEN_DOWNLOAD_CHECKS) return@collect
                    pos = newPos
                    triggerAutoDownload()
                }
            }
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (activeNetworkConnection.capabilities?.hasInternet == true) {
            triggerAutoSync()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unsyncedChangesCountSource.removeListener(unsyncedChangesListener)
        downloadProgressSource.removeListener(downloadProgressListener)
        userLoginSource.removeListener(userLoginStatusListener)
        teamModeQuestFilterSource.removeListener(teamModeChangeListener)
        coroutineScope.coroutineContext.cancelChildren()
    }

    /* ------------------------------------------------------------------------------------------ */

    private fun triggerAutoSync() {
        triggerAutoDownload()
        triggerAutoUpload()
    }

    private fun triggerAutoDownload() {
        val pos = pos ?: return
        if (activeNetworkConnection.capabilities?.hasInternet != true) return
        if (downloadProgressSource.isDownloadInProgress) return

        Log.i(TAG, "Checking whether to automatically download new quests at ${pos.latitude.format(7)},${pos.longitude.format(7)}")

        coroutineScope.launch {
            val downloadStrategy =
                if (activeNetworkConnection.capabilities?.isMetered == false) wifiDownloadStrategy
                else mobileDataDownloadStrategy
            val downloadBoundingBox = downloadStrategy.getDownloadBoundingBox(pos)
            if (downloadBoundingBox != null) {
                try {
                    downloadController.download(downloadBoundingBox)
                } catch (e: IllegalStateException) {
                    // The Android 9 bug described here should not result in a hard crash of the app
                    // https://stackoverflow.com/questions/52013545/android-9-0-not-allowed-to-start-service-app-is-in-background-after-onresume
                    Log.e(TAG, "Cannot start download service", e)
                }
            }
        }
    }

    private fun triggerAutoUpload() {
        if (!isAllowedByPreference) return
        if (activeNetworkConnection.capabilities?.hasInternet != true) return
        if (!userLoginSource.isLoggedIn) return

        coroutineScope.launch {
            try {
                uploadController.upload(isUserInitiated = false)
            } catch (e: IllegalStateException) {
                // The Android 9 bug described here should not result in a hard crash of the app
                // https://stackoverflow.com/questions/52013545/android-9-0-not-allowed-to-start-service-app-is-in-background-after-onresume
                Log.e(TAG, "Cannot start upload service", e)
            }
        }
    }

    companion object {
        private const val TAG = "QuestAutoSyncer"

        /** In metres: the distance filter this used to request for itself */
        private const val MIN_DISTANCE_BETWEEN_DOWNLOAD_CHECKS = 100.0
    }
}
