package de.westnordost.streetcomplete.data.osmtracks

import de.westnordost.streetcomplete.data.location.LocationUpdatesSource
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osmtracks.TrackRecordingStats.MIN_TRACK_ACCURACY
import de.westnordost.streetcomplete.data.osmtracks.TrackRecordingStats.NEARBY_RADIUS_METERS
import de.westnordost.streetcomplete.data.quest.VisibleQuestsSource
import de.westnordost.streetcomplete.util.ktx.nowAsEpochMilliseconds
import de.westnordost.streetcomplete.util.ktx.toLocation
import de.westnordost.streetcomplete.util.logs.Log
import de.westnordost.streetcomplete.util.math.enclosingBoundingBox
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.maplibre.compose.location.LocationEvent

/** One track recording, from the moment the user starts it to the moment they stop it.
 *
 *  iOS only, and a single rather than something the main screen owns, because a recording outlives
 *  the screen being looked at: it is the one thing in this app that deliberately keeps running
 *  while the app is in the background, so that the Live Activity has something to show and so that
 *  the GPX the user gets at the end is the walk they took rather than the parts of it they happened
 *  to be watching. Everything here is therefore free of the lifecycle - it collects the shared
 *  location stream directly, not through `repeatOnLifecycle`, and the stream hands it the
 *  background-capable provider for as long as it is recording (see [LocationUpdatesSource]).
 *
 *  Android has no counterpart: it has no background location either, and adding it there is a
 *  separate piece of work with a foreground service in it.
 *
 *  It also counts the quests near the user, which is what the Live Activity is for. That is a
 *  database query, so it is gated - see [TrackRecordingStats.shouldRecountNearbyQuests] - and runs
 *  off the collector, so a slow query cannot hold up the shared location stream that the map is
 *  collecting from as well. */
class IosTrackRecorder(
    private val locationUpdatesSource: LocationUpdatesSource,
    private val visibleQuestsSource: VisibleQuestsSource,
) {
    /** Owns its scope, the way [LocationUpdatesSource] does: it lives as long as the process, and
     *  there is nothing on iOS to cancel it in. What is started and stopped per recording is
     *  [recordingJob], not this. */
    private val scope = CoroutineScope(
        SupervisorJob() +
        CoroutineName(TAG) +
        /* on Kotlin/Native an unhandled coroutine exception takes the whole app down, and losing
           the app mid-survey is far worse than losing the count in the Live Activity */
        CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught exception", e) }
    )

    private val _session = MutableStateFlow<TrackRecordingSession?>(null)

    /** The recording in progress, or null when nothing is being recorded */
    val session: StateFlow<TrackRecordingSession?> = _session.asStateFlow()

    private var recordingJob: Job? = null

    /** Where to count the nearby quests, handed from the fix collector to the counter.
     *
     *  Conflated, so that a position that arrived while a query was still running replaces the one
     *  waiting rather than queueing behind it: the user has moved on, and a count for where they
     *  were two fixes ago is of no interest. A StateFlow would not do - it drops a value equal to
     *  the last one, and standing still for [TrackRecordingStats.RECOUNT_INTERVAL_MILLIS] is
     *  exactly a case where the same position has to be counted again. */
    private val countNearbyQuestsAt = Channel<LatLon>(Channel.CONFLATED)

    /** Starts recording. Does nothing if a recording is already running. */
    fun start() {
        if (_session.value != null) return
        _session.value = TrackRecordingSession(startedAtEpochMillis = nowAsEpochMilliseconds())
        recordingJob = scope.launch {
            launch { collectFixes() }
            launch { countNearbyQuests() }
        }
    }

    /** Stops recording and hands over what was recorded, or an empty list if nothing was.
     *
     *  The trace and the stop are one step on purpose: everything downstream reads the recording
     *  through [session], and a caller that read the points first and stopped afterwards would
     *  leave a window in which a fix arriving in between is recorded into a session nobody will
     *  ever read again - i.e. silently dropped from the GPX that goes to OSM. */
    fun stop(): List<Trackpoint> {
        val recorded = _session.value?.trackpoints.orEmpty()
        recordingJob?.cancel()
        recordingJob = null
        _session.value = null
        return recorded
    }

    private suspend fun collectFixes() {
        var lastCountedAt: LatLon? = null
        var lastCountedAtMillis = 0L
        locationUpdatesSource.updates.collect { event ->
            if (event !is LocationEvent.Update) return@collect
            val location = event.toLocation()
            // as on the main screen, and for the same reason: an imprecise fix is not a survey
            if (location.accuracy > MIN_TRACK_ACCURACY) return@collect
            val now = nowAsEpochMilliseconds()
            /* elevation 0, exactly as the main screen records it: the shared Location type has no
               altitude, so every <ele> in the uploaded trace is 0.0 until it carries one. */
            val point = Trackpoint(location.position, now, location.accuracy, 0f)
            _session.update { session ->
                if (session == null) return@update null
                session.copy(
                    trackpoints = session.trackpoints + point,
                    distanceMeters = session.distanceMeters +
                        TrackRecordingStats.addedDistanceMeters(session.trackpoints.lastOrNull(), point),
                )
            }
            /* No gap handling: a break in reception does not split a recorded track - the trace has
               to stay whole - which is the same rule the main screen follows while recording, see
               isTrackGap. A backgrounded stretch is not a gap here at all any more: that is the
               point of the recording provider. */
            if (TrackRecordingStats.shouldRecountNearbyQuests(lastCountedAt, lastCountedAtMillis, location.position, now)) {
                lastCountedAt = location.position
                lastCountedAtMillis = now
                countNearbyQuestsAt.trySend(location.position)
            }
        }
    }

    private suspend fun countNearbyQuests() {
        countNearbyQuestsAt.receiveAsFlow().collectLatest { position ->
            val nearby = withContext(Dispatchers.Default) {
                /* a box, because that is what the quest source indexes by; the circle is cut out of
                   it afterwards. Its corners reach 1.4x the radius, so this over-reads a little. */
                val quests = visibleQuestsSource.getAll(position.enclosingBoundingBox(NEARBY_RADIUS_METERS))
                TrackRecordingStats.nearbyQuests(quests, position)
            }
            /* resolved here rather than in Swift: the quest titles are Compose resources in the
               shared module, in the language the app is running in, and the widget extension does
               not link the shared module at all - it only renders the strings it is handed. */
            val nearest = nearby.nearest?.let {
                NearbyQuest(getString(it.type.title), nearby.nearestDistanceMeters)
            }
            _session.update { it?.copy(nearbyQuestCount = nearby.count, nearestQuest = nearest) }
        }
    }

    companion object {
        private const val TAG = "TrackRecorder"
    }
}

/** A recording in progress. Immutable, and replaced wholesale on every fix, so that everything
 *  watching it - the map, the Live Activity bridge - sees one consistent set of numbers. */
data class TrackRecordingSession(
    val startedAtEpochMillis: Long,
    val trackpoints: List<Trackpoint> = emptyList(),
    /** along the track as recorded, i.e. as noisy as the fixes were */
    val distanceMeters: Double = 0.0,
    val nearbyQuestCount: Int = 0,
    val nearestQuest: NearbyQuest? = null,
)

/** The nearest quest, as something that can be shown without looking anything else up */
data class NearbyQuest(
    val title: String,
    val distanceMeters: Double,
)
