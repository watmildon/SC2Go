package de.westnordost.streetcomplete.data.location

import de.westnordost.streetcomplete.util.logs.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.meters
import kotlin.time.Duration.Companion.seconds

/** The one location stream everything in the app collects.
 *
 *  On iOS, every collection of [LocationProvider.updates] is its own `CLLocationManager`, and iOS
 *  coalesces the managers in a process to the most aggressive request among them. So two
 *  collectors - the main screen and the [de.westnordost.streetcomplete.data.quest.AutoSyncer] -
 *  meant two managers, and no accuracy either of them asked for could take effect while the other
 *  asked for more. This collects `updates()` once and shares it, so that there is one manager and
 *  the [request] it is started with is decided in one place. See LOW_POWER_PLAN.md A3.
 *
 *  Changing the request restarts the manager: the previous collection is cancelled, which stops
 *  its manager, and a new one is started with the new request. The provider replays the manager's
 *  cached location on the start, so there is no gap in the stream - but that cached fix can be
 *  arbitrarily old, and the track stamps whatever it is given with *now*, so a fix older than
 *  [MAX_REPLAYED_FIX_AGE] is dropped instead.
 *
 *  Owns its scope, the way AutoSyncer does: it lives as long as the process and there is nothing
 *  on iOS to cancel it in, and the application scope is private to the platform's application
 *  class, which a common class cannot see. The sharing is started only while someone collects and
 *  stopped shortly after the last collector leaves, so the manager is not kept running by the
 *  scope being alive.
 *
 *  @param settings what the request asks for unless something overrides it. A flow, because it
 *  follows the low-power mode, which flips while the app runs: the accuracy comes from the launch
 *  flags and the distance filter from the mode unless a flag set it too - see
 *  [LocationRequestSettings.forMode], which is applied where this is wired up. The tracks,
 *  navigation and permission inputs below also change while the app runs; a change to any of
 *  them restarts the manager, as above. */
class LocationUpdatesSource(
    private val locationProvider: LocationProvider,
    settings: Flow<LocationRequestSettings>,
) {
    private val scope = CoroutineScope(
        SupervisorJob() +
        CoroutineName(TAG) +
        /* SupervisorJob only stops a failure reaching siblings, it does not swallow it, and on
           Kotlin/Native an unhandled coroutine exception takes the whole app down */
        CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught exception", e) }
    )

    /* Mirrors of MainViewModel.isRecordingTracks and .isNavigationMode, forwarded by the main
       screen. The view model cannot be injected into a single, and its flows are the state the
       screen's controls write to, so the screen collects them into these. What each overrides is
       described at [createRequest]. */
    val isRecordingTracks = MutableStateFlow(false)
    val isNavigationMode = MutableStateFlow(false)

    /** What the manager is asked for, from the settings and whatever overrides them */
    val request: Flow<LocationRequest> =
        combine(settings, isRecordingTracks, isNavigationMode) { settings, recordingTracks, navigationMode ->
            createRequest(settings, recordingTracks, navigationMode)
        }.distinctUntilChanged()

    /** The location events, one manager shared by every collector. Replays the last event to a
     *  new collector while the stream is running; when the last collector leaves, the manager is
     *  stopped after a short grace period and the replay cache is cleared with it, so nothing
     *  stale is replayed when it starts again - except by the provider itself, see above. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val updates: SharedFlow<LocationEvent> =
        /* the permission is part of what restarts the manager: a manager started while permission
           was still to be granted has only ever reported the denial */
        combine(request, locationProvider.permission) { request, permission -> request to permission }
            .distinctUntilChanged()
            .flatMapLatest { (request, _) -> locationProvider.updates(request) }
            .filterNot { it.isStaleFix() }
            /* An exception out of the provider - as opposed to an Unavailable event, which is how
               it reports problems it expects - would otherwise end the sharing coroutine for good:
               WhileSubscribed restarts on the first collector only while that coroutine is alive,
               so both collectors would be left waiting on silence. Before the stream was shared,
               each collector's lifecycle restart rebuilt its own. */
            .retryWhen { e, attempt ->
                Log.e(TAG, "Location stream failed, restarting", e)
                delay(minOf(attempt + 1, MAX_RETRY_DELAY_SECONDS).seconds)
                true
            }
            .shareIn(
                scope,
                SharingStarted.WhileSubscribed(
                    stopTimeoutMillis = STOP_TIMEOUT.inWholeMilliseconds,
                    replayExpirationMillis = 0,
                ),
                replay = 1,
            )

    private fun LocationEvent.isStaleFix(): Boolean =
        this is LocationEvent.Update && measurementMark.elapsedNow() > MAX_REPLAYED_FIX_AGE

    companion object {
        private const val TAG = "LocationUpdatesSource"

        /** How long the manager keeps running after the last collector left, so that the main
         *  screen going through a lifecycle restart does not stop and start it. */
        private val STOP_TIMEOUT = 3.seconds

        /** A fix replayed by the provider on a start older than this is dropped, see above. */
        private val MAX_REPLAYED_FIX_AGE = 30.seconds

        /** The retry delay grows by a second per failed attempt, up to this */
        private const val MAX_RETRY_DELAY_SECONDS = 10L

        /** The request for [settings], with what the two overrides force. A setting that already
         *  asks for more than an override does is left alone.
         *
         *  Both raise the accuracy tier to at least High: the track only accepts fixes accurate
         *  to 20 m, which a HundredMeters request never delivers, and following the direction of
         *  travel needs the same.
         *
         *  [recordingTracks] also forces the distance filter back to the dense, normal-mode
         *  spacing. The recorded GPX is the artefact the user pressed the button for, and it goes
         *  to OSM: it must not come out at the low-power mode's 10 m spacing. Navigation mode
         *  needs a bearing, not density, so it does not touch the filter.
         *
         *  `minimumInterval` is left at its default: it is a no-op on iOS, the library never
         *  reads it (LOW_POWER_PLAN.md B1). */
        fun createRequest(
            settings: LocationRequestSettings,
            recordingTracks: Boolean,
            navigationMode: Boolean,
        ) = LocationRequest(
            // the tiers are declared most precise first
            accuracy = if (recordingTracks || navigationMode) {
                minOf(settings.accuracy, LocationAccuracy.High)
            } else {
                settings.accuracy
            },
            minimumDistance = if (recordingTracks) {
                minOf(settings.minimumDistance, LocationRequestSettings.NORMAL_MINIMUM_DISTANCE)
            } else {
                settings.minimumDistance
            },
        )
    }
}

/** What the location request asks for when nothing overrides it */
data class LocationRequestSettings(
    val accuracy: LocationAccuracy = LocationAccuracy.High,
    val minimumDistance: Length = NORMAL_MINIMUM_DISTANCE,
) {
    companion object {
        /** The distance filter in normal mode. What it has always been; whether 5 m is enough is
         *  A4 in LOW_POWER_PLAN.md, and gated on a walk that has not happened. */
        val NORMAL_MINIMUM_DISTANCE: Length = 1.meters

        /** The distance filter in low-power mode. Cuts the fix rate and everything downstream
         *  of a fix - above all the camera animation restarted on each one while following - by
         *  about 10x at walking pace. It does nothing for the radio; that is the accuracy tier,
         *  which C3 leaves alone until the walk. */
        val LOW_POWER_MINIMUM_DISTANCE: Length = 10.meters

        /** The settings for the mode, given [flags] from the launch. The flags are the A/B
         *  harness and win, the same rule as MapPerf.maxFps: a distance flag is the distance in
         *  either mode, and only without one does the mode decide. The accuracy is the flags'
         *  in either mode: whether a lower tier still serves a survey is C3, pending the walk.
         *
         *  [flagDistanceGiven] is separate from [flags] because a flag of 1 m is
         *  indistinguishable from no flag once it has been turned into settings. */
        fun forMode(flags: LocationRequestSettings, lowPower: Boolean, flagDistanceGiven: Boolean) =
            LocationRequestSettings(
                accuracy = flags.accuracy,
                minimumDistance = when {
                    flagDistanceGiven -> flags.minimumDistance
                    lowPower -> LOW_POWER_MINIMUM_DISTANCE
                    else -> NORMAL_MINIMUM_DISTANCE
                },
            )

        /** From the launch flags, e.g. `-gpsaccuracy Balanced -gpsdistance 5`, or the defaults for
         *  each that is absent.
         *
         *  A tier that is not the name of a [LocationAccuracy] is an error, not a fallback: the
         *  flags exist for measuring one arm against another, and a typo that silently measured
         *  the default would label a High trace as whatever the typo was meant to be. The
         *  measurement script notices a process that is gone; it cannot notice a wrong setting. */
        fun fromFlags(accuracy: String?, minimumDistanceMeters: Double?) = LocationRequestSettings(
            accuracy = accuracy?.let { name ->
                LocationAccuracy.entries.find { it.name == name }
                    ?: error("Unknown -gpsaccuracy '$name'; expected one of ${LocationAccuracy.entries}")
            } ?: LocationAccuracy.High,
            minimumDistance = minimumDistanceMeters?.meters ?: NORMAL_MINIMUM_DISTANCE,
        )
    }
}
