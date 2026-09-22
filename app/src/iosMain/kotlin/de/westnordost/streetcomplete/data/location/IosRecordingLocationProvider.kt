package de.westnordost.streetcomplete.data.location

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.asMapLibreLocationMeasurement
import org.maplibre.spatialk.units.International
import platform.CoreLocation.CLActivityTypeFitness
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.CoreLocation.kCLLocationAccuracyReduced
import platform.Foundation.NSError
/* an Objective-C category member, which Kotlin/Native models as an extension: it has to be
   imported by name or it reads as an unresolved reference on a property that plainly exists */
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** maplibre's [LocationProvider], but configured for a recording session rather than for showing
 *  a dot on a map: it keeps delivering fixes while the app is in the background.
 *
 *  maplibre's own `IosLocationProvider` cannot do that, and cannot be configured to: it creates
 *  its `CLLocationManager` inside `updates()` and never sets `allowsBackgroundLocationUpdates`,
 *  so iOS stops its updates the moment the app leaves the foreground. That is the right default
 *  for the map - nobody wants the GPS on while the app is not being looked at - which is why this
 *  is a second provider picked per session (see [LocationUpdatesSource]) rather than a setting on
 *  that one.
 *
 *  Deliberately the same shape as `IosLocationProvider.updates()`, down to the `callbackFlow` on
 *  `Dispatchers.Main` and the accuracy mapping, so that a fix looks exactly the same to everything
 *  downstream whichever provider produced it; what differs is only the four properties in
 *  [configureForRecording] and how a failure is turned into a reason (its mapper is internal to
 *  the library). If maplibre ever lets a caller configure this, this class should go.
 *
 *  Permission is not reimplemented: [defaultProvider] is maplibre's own provider and its
 *  `permission` StateFlow is forwarded as-is. No new prompt is needed either - WhenInUse plus the
 *  `location` background mode in Info.plist is enough for updates that were started while the app
 *  was in the foreground, which is the only way recording can start.
 *
 *  @param defaultProvider maplibre's provider, which handles the permission for both. */
@OptIn(ExperimentalForeignApi::class)
class IosRecordingLocationProvider(
    private val defaultProvider: LocationProvider,
) : LocationProvider {

    override val permission: StateFlow<LocationPermission> get() = defaultProvider.permission

    override fun requestPermission() = defaultProvider.requestPermission()

    /* Not overriding close(): the provider that owns the permission machinery is the default one,
       and it is closed by its own Koin binding. There is nothing else to release - the manager
       lives and dies with each collection, below. */

    override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
        val manager = CLLocationManager()
        val delegate = Delegate(channel, defaultProvider.permission)
        manager.delegate = delegate
        manager.desiredAccuracy = request.accuracy.toCLLocationAccuracy()
        manager.distanceFilter = request.minimumDistance.toDouble(International.Meters)
        manager.configureForRecording()
        manager.startUpdatingLocation()
        /* Through the delegate, not two lines here, because this closure is the ONLY thing
           keeping the delegate alive: `CLLocationManager.delegate` is a weak reference, so a
           delegate nothing else refers to is collected as soon as this flow suspends, and from
           then on the manager delivers nothing. The symptom is a stream that produces one fix and
           then falls silent for good - a recording that is a single point, and a map that stops
           following. maplibre's provider is shaped the same way for the same reason. */
        awaitClose { delegate.stop(manager) }
    }.flowOn(Dispatchers.Main)

    private class Delegate(
        private val events: SendChannel<LocationEvent>,
        private val permission: StateFlow<LocationPermission>,
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            for (location in didUpdateLocations) {
                if (location !is CLLocation) continue
                events.trySend(LocationEvent.Update(
                    measurement = location.asMapLibreLocationMeasurement(),
                    /* a mark on the monotonic clock, as maplibre's provider also hands over, so
                       that toLocation() can subtract it from its origin exactly rather than
                       falling back to reading the clock twice. See ktx/Location.kt. */
                    measurementMark = TimeSource.Monotonic.markNow() - location.ageAtReceipt(),
                ))
            }
        }

        /** Stops [manager] and detaches from it. See the call site for why it is a method here. */
        fun stop(manager: CLLocationManager) {
            manager.stopUpdatingLocation()
            manager.delegate = null
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            events.trySend(LocationEvent.Unavailable(
                reason = didFailWithError.toUnavailableReason(),
                cause = null,
            ))
        }

        /** What the failure means, the way maplibre's own (internal) `asUnavailableReason` does.
         *
         *  `kCLErrorDenied` covers both "this app may not" and "location services are off device
         *  wide", and the two lead to different messages on the main screen. maplibre tells them
         *  apart by reading `CLLocationManager.locationServicesEnabled()`, which Apple documents
         *  as blocking and which it therefore has to do off the main thread - from a delegate
         *  callback that is not an option, so the permission the default provider is already
         *  tracking decides instead. */
        private fun NSError.toUnavailableReason(): LocationUnavailableReason = when {
            domain != kCLErrorDomain -> LocationUnavailableReason.UnexpectedFailure
            code == kCLErrorDenied ->
                if (permission.value is LocationPermission.NotGranted) {
                    LocationUnavailableReason.PermissionDenied
                } else {
                    LocationUnavailableReason.ServicesDisabled
                }
            // no fix yet, or none for a while - routine in a tunnel, and not a reason to stop
            code == kCLErrorLocationUnknown -> LocationUnavailableReason.TemporarilyUnavailable
            else -> LocationUnavailableReason.UnexpectedFailure
        }
    }
}

/** What separates a recording session from showing a dot on the map. */
@OptIn(ExperimentalForeignApi::class)
private fun CLLocationManager.configureForRecording() {
    /* the whole point: without this iOS stops delivering as soon as the app is backgrounded, and
       the Live Activity - which is only ever shown while the app is NOT in the foreground - would
       stand still. Requires the `location` background mode in Info.plist; setting it without that
       throws. */
    allowsBackgroundLocationUpdates = true
    /* the blue pill / status bar indicator while we track them in the background. Not a setting to
       save: it is the honest thing to show, and iOS shows it regardless for background updates. */
    showsBackgroundLocationIndicator = true
    /* iOS pauses updates by itself when it thinks the user has stopped moving, and resumes them
       only on significant movement - minutes of a survey silently missing from the GPX. The user
       pressed record; they decide when it stops. */
    pausesLocationUpdatesAutomatically = false
    /* tells iOS what the movement will look like, which is what it tunes the filtering to. A
       survey on foot is what tracks are recorded on. */
    activityType = CLActivityTypeFitness
}

/** How old the fix was when it arrived. [CLLocation.timestamp] is when it was taken, which can be
 *  a while back for the manager's cached first fix. maplibre computes the same thing, but its
 *  `ageAtReceipt()` is internal to the library.
 *
 *  Never negative: a clock adjustment between the fix and now must not produce a mark in the
 *  future, which would make the fix look newer than one taken after it. */
@OptIn(ExperimentalForeignApi::class)
private fun CLLocation.ageAtReceipt(): Duration =
    (-timestamp.timeIntervalSinceNow).coerceAtLeast(0.0).seconds

/** maplibre's mapping from its tiers to Core Location's, kept identical on purpose: the recording
 *  session must ask the radio for exactly what the same [LocationRequest] would have asked for
 *  through the default provider, or switching between them mid-session would change the accuracy
 *  as a side effect. */
private fun LocationAccuracy.toCLLocationAccuracy(): Double = when (this) {
    LocationAccuracy.BestForNavigation -> kCLLocationAccuracyBestForNavigation
    LocationAccuracy.High -> kCLLocationAccuracyBest
    LocationAccuracy.Balanced -> kCLLocationAccuracyHundredMeters
    LocationAccuracy.Low -> kCLLocationAccuracyKilometer
    LocationAccuracy.Lowest -> kCLLocationAccuracyReduced
}
