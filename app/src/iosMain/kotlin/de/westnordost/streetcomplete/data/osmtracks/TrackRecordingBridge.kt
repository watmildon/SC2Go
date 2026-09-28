package de.westnordost.streetcomplete.data.osmtracks

import de.westnordost.streetcomplete.util.logs.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import kotlin.math.round
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/* The Kotlin half of the Live Activity.
 *
 * ActivityKit is Swift-only - Kotlin/Native cannot call it at all - so the split is: Kotlin works
 * out what to show, Swift shows it. This file is the whole of the seam, and it is deliberately
 * plain: a callback and a handle to stop it with, no flows, no suspend functions and no Kotlin
 * types Swift has to know how to unwrap. */

/** What the Live Activity shows, in the shape Swift wants it.
 *
 *  A separate type from [TrackRecordingSession] rather than exporting that one: the session holds
 *  the whole list of trackpoints, which Swift has no use for and which would be bridged on every
 *  update, and Kotlin/Native exports a `Double?` as an `NSNumber` that the widget would then have
 *  to unbox. */
data class TrackRecordingSnapshot(
    val startedAtEpochMillis: Long,
    val distanceMeters: Double,
    val nearbyQuestCount: Int,
    val nearestQuestTitle: String?,
    /** negative when there is no nearest quest, see [nearestQuestTitle] */
    val nearestQuestDistanceMeters: Double,
)

/** Reports the track recording to Swift: a snapshot when one starts, whenever what it shows
 *  changes, and null when it stops.
 *
 *  Delivered on the main thread, which is where ActivityKit has to be called from anyway.
 *
 *  **Coalesced**: starting and stopping are reported at once, but while a recording runs no more
 *  than one update every [MIN_UPDATE_INTERVAL] is delivered, and only if something in the snapshot
 *  actually changed - except for a heartbeat every [HEARTBEAT_INTERVAL], which repeats the last
 *  snapshot so that the activity never looks stale while the process is alive. An ActivityKit
 *  update is a system-wide render, and the one thing that would otherwise change on every single
 *  fix - the elapsed time - is not sent at all: the widget is given the start time and lets
 *  SwiftUI count up from it.
 *
 *  @return a handle whose `close()` stops the reporting. Nothing in the app calls it today - the
 *  observation lasts as long as the process - but leaving no way to stop it would make this
 *  impossible to use from a test or a preview. */
fun observeTrackRecording(onChange: (TrackRecordingSnapshot?) -> Unit): TrackRecordingObservation {
    val recorder = KoinPlatform.getKoin().get<IosTrackRecorder>()
    val scope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.Main +
        CoroutineName(TAG) +
        // an unhandled coroutine exception takes the whole app down on Kotlin/Native
        CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught exception", e) }
    )
    scope.launch {
        var lastSentAt = TimeSource.Monotonic.markNow() - MIN_UPDATE_INTERVAL
        var lastSent: TrackRecordingSnapshot? = null
        recorder.session
            .map { it?.toSnapshot() }
            .distinctUntilChanged()
            /* collectLatest, so that a newer snapshot cancels the wait below rather than queueing
               behind it: what is delivered is always the newest, and the last one before a lull is
               always delivered eventually because nothing comes along to supersede it. */
            .collectLatest { snapshot ->
                // starting and stopping are what the user sees appear and disappear: never delayed
                val startedOrStopped = (snapshot == null) != (lastSent == null)
                if (!startedOrStopped) {
                    val wait = MIN_UPDATE_INTERVAL - lastSentAt.elapsedNow()
                    if (wait > Duration.ZERO) delay(wait)
                }
                lastSent = snapshot
                lastSentAt = TimeSource.Monotonic.markNow()
                onChange(snapshot)
                /* Heartbeat: the same snapshot again every HEARTBEAT_INTERVAL for as long as
                   nothing supersedes it. Swift stamps every delivery with a fresh stale date, and
                   without this a recording that is alive but unchanging - the user standing still,
                   or indoors where every fix fails the accuracy filter - would go stale on the
                   Lock Screen after two minutes, because with the 1 m distance filter a standing
                   user produces no fixes and so no new snapshot. A killed or suspended process
                   stops the heartbeat too, so a real orphan still goes stale as intended. Only
                   while recording: after a stop there is no activity to keep fresh. */
                if (snapshot != null) {
                    while (true) {
                        delay(HEARTBEAT_INTERVAL)
                        lastSentAt = TimeSource.Monotonic.markNow()
                        onChange(snapshot)
                    }
                }
            }
    }
    return TrackRecordingObservation(scope)
}

/** The handle [observeTrackRecording] hands back. A class rather than `AutoCloseable` so that it
 *  is an ordinary object with a `close()` on the Swift side. */
class TrackRecordingObservation internal constructor(private val scope: CoroutineScope) {
    fun close() {
        scope.cancel()
    }
}

private fun TrackRecordingSession.toSnapshot() = TrackRecordingSnapshot(
    startedAtEpochMillis = startedAtEpochMillis,
    /* rounded to the metre, here rather than in the widget, because this is also what decides
       whether anything changed: a fix that moved the total by 20 cm is not worth an update. */
    distanceMeters = round(distanceMeters),
    nearbyQuestCount = nearbyQuestCount,
    nearestQuestTitle = nearestQuest?.title,
    /* a negative number rather than null: Kotlin/Native would export a Double? as an NSNumber?,
       and the widget - which does not link this framework - would be handed something it has to
       unbox before it can format it */
    nearestQuestDistanceMeters = nearestQuest?.let { round(it.distanceMeters) } ?: -1.0,
)

private const val TAG = "TrackRecordingBridge"

/** The most often the Live Activity is updated while a recording runs. */
private val MIN_UPDATE_INTERVAL = 5.seconds

/** How often an unchanged snapshot is re-sent while a recording runs, so that the activity's
 *  stale date (two minutes, set on the Swift side) keeps being pushed out. Well inside it, so
 *  that one delayed delivery does not tip the activity into stale. */
private val HEARTBEAT_INTERVAL = 45.seconds
