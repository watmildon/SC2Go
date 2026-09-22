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
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment
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

    /** The points recorded so far, appended to in place.
     *
     *  Not in [TrackRecordingSession], which is replaced wholesale on every fix: copying the list
     *  into each new session would make one fix cost a pass over every fix before it - hours of
     *  them, by the end of a survey - and would hand that list to every observer of [session],
     *  including the Live Activity bridge, which has no use for it. The session carries only the
     *  count and the running totals; the points themselves are read through [trackpoints].
     *
     *  Guarded by a lock because it is appended to on the collector's dispatcher and read from the
     *  main thread while the map draws it. */
    private val trackpointsLock = ReentrantLock()
    private val trackpoints = mutableListOf<Trackpoint>()

    /** Where to count the nearby quests, handed from the fix collector to the counter.
     *
     *  Conflated, so that a position that arrived while a query was still running replaces the one
     *  waiting rather than queueing behind it: the user has moved on, and a count for where they
     *  were two fixes ago is of no interest. A StateFlow would not do - it drops a value equal to
     *  the last one, and standing still for [TrackRecordingStats.RECOUNT_INTERVAL_MILLIS] is
     *  exactly a case where the same position has to be counted again. */
    private val countNearbyQuestsAt = Channel<LatLon>(Channel.CONFLATED)

    /** What has been recorded so far, for drawing. A copy, because the list it comes from is still
     *  being appended to on another thread; taken at most once per fix, when the map notices that
     *  [TrackRecordingSession.trackpointCount] has changed. */
    fun trackpoints(): List<Trackpoint> = trackpointsLock.withLock { trackpoints.toList() }

    /** Starts recording. Does nothing if a recording is already running. */
    fun start() {
        if (_session.value != null) return
        trackpointsLock.withLock { trackpoints.clear() }
        _session.value = TrackRecordingSession(startedAtEpochMillis = nowAsEpochMilliseconds())
        val job = scope.launch {
            /* supervisorScope so that the two cannot cancel one another: a failure in the count -
               a database query, on data that is being written to underneath it - used to take the
               fix collector down with it, i.e. silently end the recording while the stop button
               and the Live Activity carried on as if it were running. */
            supervisorScope {
                launch { countNearbyQuests() }
                /* the fix collector is the recording itself, so it is this coroutine rather than a
                   third child: when it ends there is nothing left to record, and the job ending is
                   what the handler below turns into the session ending. */
                collectFixes()
            }
        }
        recordingJob = job
        job.invokeOnCompletion { cause ->
            /* A failure only - an ordinary cancellation is stop() below, which does this itself.
               The session is what the stop button, the drawn track and the Live Activity are all
               read from, so a recording that has died has to clear it rather than leave them
               showing a walk that nothing is recording any more. The points recorded up to that
               moment are kept: [trackpoints] is not cleared here, so stop() still hands them over.

               Guarded on the job because a completion handler can run after start() has been
               called again, and it must not end the recording that replaced this one. */
            if (cause != null && cause !is CancellationException && recordingJob === job) {
                Log.e(TAG, "The recording failed, ending it", cause)
                recordingJob = null
                _session.value = null
            }
        }
    }

    /** Stops recording and hands over what was recorded, or an empty list if nothing was.
     *
     *  The trace and the stop are one step on purpose: everything downstream reads the recording
     *  through [session], and a caller that read the points first and stopped afterwards would
     *  leave a window in which a fix arriving in between is recorded into a session nobody will
     *  ever read again - i.e. silently dropped from the GPX that goes to OSM.
     *
     *  Not atomic with the collector, which runs on another dispatcher: at most one fix - one that
     *  was already being turned into a trackpoint when this was called - can be lost. Which of the
     *  two gets the lock first decides whether it is in the returned list or nowhere; what cannot
     *  happen is that it leaks into the next recording, because the collector checks [session]
     *  under the same lock. */
    fun stop(): List<Trackpoint> {
        recordingJob?.cancel()
        recordingJob = null
        /* before the lock, so that a fix that takes the lock first is either fully in the list
           handed over below or discarded, never appended after it was taken */
        _session.value = null
        /* the recount channel is conflated, so it holds at most one position - the last one of
           this recording, which would otherwise be the first thing the next one counted */
        countNearbyQuestsAt.tryReceive()
        return trackpointsLock.withLock {
            val recorded = trackpoints.toList()
            trackpoints.clear()
            recorded
        }
    }

    private suspend fun collectFixes() {
        var lastCountedAt: LatLon? = null
        var lastCountedAtMillis = 0L
        locationUpdatesSource.updates.collect { event ->
            if (event !is LocationEvent.Update) {
                /* Loud, because of what it can mean while recording: the shared stream restarting
                   - a permission change, or the provider throwing - while the app is in the
                   background ends the background-capable collection, and the recording then goes
                   on producing nothing until the app is looked at again. Nothing here can fix
                   that; a line in the log is what makes it recognisable afterwards. */
                if (event is LocationEvent.Unavailable) {
                    Log.w(TAG, "Location unavailable while recording: ${event.reason}")
                }
                return@collect
            }
            val location = event.toLocation()
            /* as on the main screen, and for the same reason: an imprecise fix is not a survey.
               The lower bound is Core Location's: it reports a negative horizontalAccuracy when
               the fix is invalid, which maplibre passes through as-is. */
            if (location.accuracy !in 0f..MIN_TRACK_ACCURACY) return@collect
            val now = nowAsEpochMilliseconds()
            /* elevation 0, exactly as the main screen records it: the shared Location type has no
               altitude, so every <ele> in the uploaded trace is 0.0 until it carries one. */
            val point = Trackpoint(location.position, now, location.accuracy, 0f)
            val addedDistance = trackpointsLock.withLock {
                // the recording ended while this fix was being processed, see stop()
                if (_session.value == null) return@withLock null
                val added = TrackRecordingStats.addedDistanceMeters(trackpoints.lastOrNull(), point)
                trackpoints.add(point)
                added
            } ?: return@collect
            _session.update { session ->
                session?.copy(
                    trackpointCount = session.trackpointCount + 1,
                    distanceMeters = session.distanceMeters + addedDistance,
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
        /* Resolved once, here, rather than letting the one-argument getString below resolve it per
           count: working out which resources apply reads UIScreen.mainScreen and its trait
           collection, which is UIKit and may only be touched on the main thread, while everything
           in this class runs on Dispatchers.Default. Once means one hop to the main thread per
           recording rather than one per count - and none at all in the common case of a recording
           with no quest nearby. */
        val resourceEnvironment = withContext(Dispatchers.Main) { getSystemResourceEnvironment() }
        countNearbyQuestsAt.receiveAsFlow().collectLatest { position ->
            /* Per count, and not fatal: this is the number in the Live Activity, and a query that
               failed once - against a database the download is writing to underneath it - is no
               reason to stop recording the track, which is what the user actually pressed the
               button for. The next fix tries again. */
            try {
                val nearby = withContext(Dispatchers.Default) {
                    /* a box, because that is what the quest source indexes by; the circle is cut
                       out of it afterwards. Its corners reach 1.4x the radius, so this over-reads
                       a little. */
                    val quests = visibleQuestsSource.getAll(position.enclosingBoundingBox(NEARBY_RADIUS_METERS))
                    TrackRecordingStats.nearbyQuests(quests, position)
                }
                /* resolved here rather than in Swift: the quest titles are Compose resources in the
                   shared module, in the language the app is running in, and the widget extension does
                   not link the shared module at all - it only renders the strings it is handed. */
                val nearest = nearby.nearest?.let {
                    NearbyQuest(
                        getString(resourceEnvironment, it.type.title),
                        nearby.nearestDistanceMeters,
                    )
                }
                _session.update { it?.copy(nearbyQuestCount = nearby.count, nearestQuest = nearest) }
            } catch (e: CancellationException) {
                // a newer position superseded this one, or the recording stopped: not a failure
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not count the quests nearby", e)
            }
        }
    }

    companion object {
        private const val TAG = "TrackRecorder"
    }
}

/** A recording in progress. Immutable, and replaced wholesale on every fix, so that everything
 *  watching it - the map, the Live Activity bridge - sees one consistent set of numbers.
 *
 *  The recorded points are deliberately not in here; see `IosTrackRecorder.trackpoints`. */
data class TrackRecordingSession(
    val startedAtEpochMillis: Long,
    /** how many points have been recorded, which is also what tells a reader of
     *  `IosTrackRecorder.trackpoints()` that there is something new to draw */
    val trackpointCount: Int = 0,
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
