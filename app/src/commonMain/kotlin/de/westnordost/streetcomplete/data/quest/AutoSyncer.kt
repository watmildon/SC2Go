package de.westnordost.streetcomplete.data.quest

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.westnordost.streetcomplete.data.UnsyncedChangesCountSource
import de.westnordost.streetcomplete.data.connection.ActiveNetworkConnection
import de.westnordost.streetcomplete.data.connection.NetworkCapabilities
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val coroutineScope = CoroutineScope(SupervisorJob() + CoroutineName("AutoSyncer"))

    private val networkCapabilities = MutableStateFlow<NetworkCapabilities?>(null)

    private var pos: LatLon? = null

    /** What [onCreate] launched, so that a second owner does not add a second set of collectors */
    private var observerJobs: List<Job> = emptyList()

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
        Autosync.WIFI -> networkCapabilities.value?.isMetered == false
        Autosync.OFF -> false
    }

    /* ---------------------------------------- Lifecycle --------------------------------------- */

    /* Upstream (902a349ed) made this a plain object owned by MainViewModel: everything starts in
       init {} and stops in onClear(). Two of the three things kept below cannot hold under that
       shape, so it is a hybrid - upstream's ownership and teardown, our lifecycle gate:

       - The location collection has to stop when the app is not STARTED. It collects the *shared*
         LocationUpdatesSource, which is shared with SharingStarted.WhileSubscribed, so a collector
         that never leaves keeps the one CLLocationManager running for the life of the process -
         including while the app is in the background. That is the battery regression measured in
         LOW_POWER_PLAN.md; init {} would reintroduce it.
       - Same for the network capabilities: IosActiveNetworkConnection.capabilities is a cold
         callbackFlow that starts an NWPathMonitor per collection.
       - onResume's sync trigger has no equivalent in upstream's shape at all; coming back to the
         app is exactly when a sync is wanted.

       The listener registration is in init {} / onClear() as upstream has it: their span used to
       be onCreate..onDestroy, which is the same span as construction..onClear here. */

    init {
        unsyncedChangesCountSource.addListener(unsyncedChangesListener)
        downloadProgressSource.addListener(downloadProgressListener)
        userLoginSource.addListener(userLoginStatusListener)
        teamModeQuestFilterSource.addListener(teamModeChangeListener)
    }

    override fun onCreate(owner: LifecycleOwner) {
        /* This is a Koin single while the lifecycle owner is not: on Android the activity is
           recreated on a configuration change and observes the same instance again, and the view
           model - which is what calls onClear - survives that. Without cancelling first, every
           recreation would add another collector of the shared location stream. */
        observerJobs.forEach { it.cancel() }

        val networkJob = coroutineScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activeNetworkConnection.capabilities.collect { capabilities ->
                    networkCapabilities.value = capabilities

                    if (capabilities?.hasInternet == true) {
                        triggerAutoSync()
                    }
                }
            }
        }
        val locationJob = coroutineScope.launch {
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
                    if (locationEvent !is LocationEvent.Update) return@collect
                    val (position, accuracy) = locationEvent.measurement
                    if (accuracy != null && accuracy >= 300.meters) return@collect
                    val newPos = LatLon(position.latitude, position.longitude)
                    val lastPos = pos
                    if (lastPos != null && lastPos.distanceTo(newPos) < MIN_DISTANCE_BETWEEN_DOWNLOAD_CHECKS) return@collect
                    pos = newPos
                    triggerAutoDownload()
                }
            }
        }
        observerJobs = listOf(networkJob, locationJob)
    }

    override fun onResume(owner: LifecycleOwner) {
        if (networkCapabilities.value?.hasInternet == true) {
            triggerAutoSync()
        }
    }

    /** Called by MainViewModel when it is cleared. Also cancels what [onCreate] started, so that
     *  it is safe for the lifecycle observer never to be removed. */
    fun onClear() {
        unsyncedChangesCountSource.removeListener(unsyncedChangesListener)
        downloadProgressSource.removeListener(downloadProgressListener)
        userLoginSource.removeListener(userLoginStatusListener)
        teamModeQuestFilterSource.removeListener(teamModeChangeListener)
        observerJobs = emptyList()
        coroutineScope.coroutineContext.cancelChildren()
    }

    /* ------------------------------------------------------------------------------------------ */

    private fun triggerAutoSync() {
        triggerAutoDownload()
        triggerAutoUpload()
    }

    private fun triggerAutoDownload() {
        val pos = pos ?: return
        if (networkCapabilities.value?.hasInternet != true) return
        if (downloadProgressSource.isDownloadInProgress) return

        Log.i(TAG, "Checking whether to automatically download new quests at ${pos.latitude.format(7)},${pos.longitude.format(7)}")

        coroutineScope.launch {
            val downloadStrategy =
                if (networkCapabilities.value?.isMetered == false) wifiDownloadStrategy
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
        if (networkCapabilities.value?.hasInternet != true) return
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
        private const val TAG = "AutoSyncer"

        /** In metres: the distance filter this used to request for itself */
        private const val MIN_DISTANCE_BETWEEN_DOWNLOAD_CHECKS = 100.0
    }
}
