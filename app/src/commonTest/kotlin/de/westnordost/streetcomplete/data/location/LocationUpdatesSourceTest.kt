package de.westnordost.streetcomplete.data.location

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationMeasurement
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.International
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.meters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/** The request the shared stream starts the location manager with. The provider is a fake that
 *  never delivers anything: what is under test is which request it would be started with. */
class LocationUpdatesSourceTest {

    private val provider = object : LocationProvider {
        override fun updates(request: LocationRequest): Flow<LocationEvent> = emptyFlow()
    }

    @Test fun defaultsWithoutFlags() {
        val settings = LocationRequestSettings.fromFlags(null, null)
        assertEquals(LocationAccuracy.High, settings.accuracy)
        assertEquals(1.0, settings.minimumDistance.inMeters())
    }

    @Test fun readsFlags() {
        val settings = LocationRequestSettings.fromFlags("Balanced", 5.0)
        assertEquals(LocationAccuracy.Balanced, settings.accuracy)
        assertEquals(5.0, settings.minimumDistance.inMeters())
    }

    /** A typo in the flag must not silently measure the default under the wrong label */
    @Test fun unknownAccuracyFlagFailsFast() {
        assertFailsWith<IllegalStateException> { LocationRequestSettings.fromFlags("Balenced", null) }
    }

    /* --------------------------------- settings per mode ---------------------------------- */

    /** The flag is the A/B harness: whatever it says is measured, in either mode */
    @Test fun forModeUsesTheFlagDistanceInEitherMode() {
        val flags = LocationRequestSettings.fromFlags("Balanced", 5.0)
        val normal = LocationRequestSettings.forMode(flags, lowPower = false, flagDistanceGiven = true)
        val lowPower = LocationRequestSettings.forMode(flags, lowPower = true, flagDistanceGiven = true)
        assertEquals(5.0, normal.minimumDistance.inMeters())
        assertEquals(5.0, lowPower.minimumDistance.inMeters())
        assertEquals(LocationAccuracy.Balanced, normal.accuracy)
        assertEquals(LocationAccuracy.Balanced, lowPower.accuracy)
    }

    @Test fun forModeUsesTenMetresInLowPowerWithoutAFlag() {
        val flags = LocationRequestSettings.fromFlags(null, null)
        val settings = LocationRequestSettings.forMode(flags, lowPower = true, flagDistanceGiven = false)
        assertEquals(10.0, settings.minimumDistance.inMeters())
        // the accuracy is not the mode's to change until C3 is decided
        assertEquals(LocationAccuracy.High, settings.accuracy)
    }

    @Test fun forModeUsesOneMetreInNormalModeWithoutAFlag() {
        val flags = LocationRequestSettings.fromFlags(null, null)
        val settings = LocationRequestSettings.forMode(flags, lowPower = false, flagDistanceGiven = false)
        assertEquals(1.0, settings.minimumDistance.inMeters())
        assertEquals(LocationAccuracy.High, settings.accuracy)
    }

    /* ------------------------------------ the overrides ----------------------------------- */

    @Test fun requestIsTheSettingsWhenNothingOverridesThem() = runBlocking {
        val source = LocationUpdatesSource(provider, flowOf(LocationRequestSettings(LocationAccuracy.Balanced, 5.meters)))
        val request = source.request.first()
        assertEquals(LocationAccuracy.Balanced, request.accuracy)
        assertEquals(5.0, request.minimumDistance.inMeters())
    }

    /** The recorded GPX goes to OSM: it must not be sparse because the phone was saving power */
    @Test fun recordingTracksForcesHighAccuracyAndTheDenseFilter() = runBlocking {
        val source = LocationUpdatesSource(provider, flowOf(LocationRequestSettings(LocationAccuracy.Balanced, 10.meters)))
        source.isRecordingTracks.value = true
        val request = source.request.first()
        assertEquals(LocationAccuracy.High, request.accuracy)
        assertEquals(1.0, request.minimumDistance.inMeters())
    }

    /** Following the direction of travel needs a bearing, not density */
    @Test fun navigationModeForcesHighAccuracyOnly() = runBlocking {
        val source = LocationUpdatesSource(provider, flowOf(LocationRequestSettings(LocationAccuracy.Balanced, 10.meters)))
        source.isNavigationMode.value = true
        val request = source.request.first()
        assertEquals(LocationAccuracy.High, request.accuracy)
        assertEquals(10.0, request.minimumDistance.inMeters())
    }

    @Test fun requestFollowsTheOverridesBackDown() = runBlocking {
        val source = LocationUpdatesSource(provider, flowOf(LocationRequestSettings(LocationAccuracy.Balanced, 10.meters)))
        source.isRecordingTracks.value = true
        assertEquals(LocationAccuracy.High, source.request.first().accuracy)
        assertEquals(1.0, source.request.first().minimumDistance.inMeters())
        source.isRecordingTracks.value = false
        assertEquals(LocationAccuracy.Balanced, source.request.first().accuracy)
        assertEquals(10.0, source.request.first().minimumDistance.inMeters())
    }

    @Test fun forcingHighDoesNotLowerABetterTier() = runBlocking {
        val source = LocationUpdatesSource(provider, flowOf(LocationRequestSettings(LocationAccuracy.BestForNavigation, 1.meters)))
        source.isRecordingTracks.value = true
        assertEquals(LocationAccuracy.BestForNavigation, source.request.first().accuracy)
    }

    @Test fun forcingTheDenseFilterDoesNotLoosenADenserOne() = runBlocking {
        val source = LocationUpdatesSource(provider, flowOf(LocationRequestSettings(LocationAccuracy.High, 0.5.meters)))
        source.isRecordingTracks.value = true
        assertEquals(0.5, source.request.first().minimumDistance.inMeters())
    }

    /** The tiers are compared by ordinal, so this is only correct while they stay declared most
     *  precise first, as in maplibre-compose 0.15.0. */
    @Test fun accuracyTiersAreDeclaredMostPreciseFirst() {
        assertEquals(
            listOf(
                LocationAccuracy.BestForNavigation, LocationAccuracy.High, LocationAccuracy.Balanced,
                LocationAccuracy.Low, LocationAccuracy.Lowest
            ),
            LocationAccuracy.entries.toList()
        )
    }

    /* ------------------------------- the shared stream itself ------------------------------- */

    @Test fun modeFlipRestartsTheManager() = runBlocking {
        val provider = RecordingLocationProvider(granted)
        val source = LocationUpdatesSource(provider, flowOf(LocationRequestSettings(LocationAccuracy.Balanced, 5.meters)))
        val collector = launch { source.updates.collect {} }
        provider.calls.first { it.size == 1 }

        source.isNavigationMode.value = true

        val calls = withTimeout(5.seconds) { provider.calls.first { it.size == 2 } }
        assertEquals(listOf(LocationAccuracy.Balanced, LocationAccuracy.High), calls.map { it.request.accuracy })
        assertTrue(calls[0].cancelled.value, "the first manager was not stopped")
        assertFalse(calls[1].cancelled.value)
        collector.cancel()
    }

    /** The settings follow the low-power mode, which flips while the app runs */
    @Test fun settingsChangeRestartsTheManager() = runBlocking {
        val provider = RecordingLocationProvider(granted)
        val settings = MutableStateFlow(LocationRequestSettings(LocationAccuracy.High, 1.meters))
        val source = LocationUpdatesSource(provider, settings)
        val collector = launch { source.updates.collect {} }
        provider.calls.first { it.size == 1 }

        settings.value = LocationRequestSettings(LocationAccuracy.High, 10.meters)

        val calls = withTimeout(5.seconds) { provider.calls.first { it.size == 2 } }
        assertEquals(listOf(1.0, 10.0), calls.map { it.request.minimumDistance.inMeters() })
        assertTrue(calls[0].cancelled.value, "the first manager was not stopped")
        assertFalse(calls[1].cancelled.value)
        collector.cancel()
    }

    @Test fun permissionGrantRestartsTheManager() = runBlocking {
        val provider = RecordingLocationProvider(LocationPermission.NotGranted(canRequest = true))
        val source = LocationUpdatesSource(provider, flowOf(LocationRequestSettings()))
        val collector = launch { source.updates.collect {} }
        provider.calls.first { it.size == 1 }

        provider.permission.value = granted

        val calls = withTimeout(5.seconds) { provider.calls.first { it.size == 2 } }
        // same request both times: it is the grant alone that restarts it
        assertEquals(calls[0].request, calls[1].request)
        assertTrue(calls[0].cancelled.value, "the first manager was not stopped")
        collector.cancel()
    }

    /** The provider replays the manager's cached fix on every start, however old it is */
    @Test fun staleFixIsDropped() = runBlocking {
        val provider = RecordingLocationProvider(granted)
        val source = LocationUpdatesSource(provider, flowOf(LocationRequestSettings()))
        provider.events.send(fixTaken(60.seconds.ago()))
        provider.events.send(fixTaken(1.seconds.ago()))

        val first = withTimeout(5.seconds) { source.updates.first() }

        val fix = assertIs<LocationEvent.Update>(first)
        assertTrue(fix.measurementMark.elapsedNow() < 30.seconds, "the stale fix came through")
    }

    private val granted = LocationPermission.Granted(LocationAccuracyAuthorization.Precise)

    private fun Duration.ago() = TimeSource.Monotonic.markNow() - this

    private fun fixTaken(at: TimeSource.Monotonic.ValueTimeMark) = LocationEvent.Update(
        LocationMeasurement(
            position = Position(0.0, 0.0),
            horizontalAccuracy = 5.meters,
            measuredAt = Instant.fromEpochSeconds(0),
        ),
        at,
    )

    private fun Length.inMeters() = toDouble(International.Meters)
}

/** Records every collection of [updates] the way IosLocationProvider would start a location
 *  manager for it, and delivers whatever is put into [events] to the current one. */
private class RecordingLocationProvider(initialPermission: LocationPermission) : LocationProvider {
    class Call(val request: LocationRequest) {
        val cancelled = MutableStateFlow(false)
    }

    override val permission = MutableStateFlow(initialPermission)
    val calls = MutableStateFlow<List<Call>>(emptyList())
    val events = Channel<LocationEvent>(Channel.UNLIMITED)

    override fun updates(request: LocationRequest): Flow<LocationEvent> = channelFlow {
        val call = Call(request)
        calls.update { it + call }
        try {
            for (event in events) send(event)
        } finally {
            call.cancelled.value = true
        }
    }
}
