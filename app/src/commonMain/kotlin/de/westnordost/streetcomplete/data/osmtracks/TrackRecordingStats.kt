package de.westnordost.streetcomplete.data.osmtracks

import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.quest.Quest
import de.westnordost.streetcomplete.util.math.distanceTo

/** The arithmetic behind what a track recording shows while it runs: how far the user has walked
 *  and how many quests are within reach of where they are.
 *
 *  Pure, and in commonMain, only so that it can be tested: the recording itself is iOS-only (see
 *  `IosTrackRecorder`), because the Live Activity it feeds is. Everything here is a function of
 *  its arguments - no clock, no database, no coroutines - which is the whole point. */
object TrackRecordingStats {

    /** Fixes less precise than this do not go on a track, as on Android.
     *
     *  Here rather than in the screen that used to hold it, because both the drawn track and the
     *  recorded one now apply it and they are in different files. */
    const val MIN_TRACK_ACCURACY = 20f

    /** How far a quest may be from the user to count as "nearby".
     *
     *  50 m is about what can be surveyed from where you are standing without walking to it, and
     *  it is small enough that the number changes as the user walks, which is what makes it worth
     *  showing at all. It is also the radius the bounding box handed to the quest source is built
     *  from, so widening it costs a wider query. */
    const val NEARBY_RADIUS_METERS = 50.0

    /** How far the user has to have walked before the nearby quests are counted again. */
    const val RECOUNT_DISTANCE_METERS = 10.0

    /** How long may pass before the nearby quests are counted again even standing still. Not zero
     *  because quests appear and disappear under a standing user too - a download finishing, or
     *  the one in front of them being solved. */
    const val RECOUNT_INTERVAL_MILLIS = 10_000L

    /** How much longer the track becomes when [next] is appended to a track whose last point is
     *  [last], or the whole of it if there is no last point yet.
     *
     *  The recorder adds this up as the fixes arrive rather than re-summing the list, which would
     *  make every fix cost a pass over the whole survey - hours of them, by the end. */
    fun addedDistanceMeters(last: Trackpoint?, next: Trackpoint): Double =
        if (last == null) 0.0 else last.position.distanceTo(next.position)

    /** The length of [track], i.e. what repeatedly adding [addedDistanceMeters] comes to.
     *
     *  Along the track as recorded, so it is as noisy as the fixes are: a stationary phone with a
     *  wandering fix still accumulates metres. Nothing here filters that out; the distance filter
     *  on the location request is what keeps it small. */
    fun totalDistanceMeters(track: List<Trackpoint>): Double {
        var total = 0.0
        for (i in 1..track.lastIndex) total += addedDistanceMeters(track[i - 1], track[i])
        return total
    }

    /** Whether the nearby quests are worth counting again now that the user is at [position].
     *
     *  The count is a database query and a distance per quest, on every fix if nothing gates it,
     *  while the whole point of the recording is that it runs for hours in the user's pocket. So
     *  it is redone only once the user has moved far enough for the answer to plausibly differ,
     *  or enough time has passed that it may have changed underneath them.
     *
     *  @param lastCountedAt where the user was when the count was last done, or null if never
     *  @param lastCountedAtMillis when that was
     *  @param nowMillis the current time */
    fun shouldRecountNearbyQuests(
        lastCountedAt: LatLon?,
        lastCountedAtMillis: Long,
        position: LatLon,
        nowMillis: Long,
    ): Boolean =
        lastCountedAt == null ||
        lastCountedAt.distanceTo(position) >= RECOUNT_DISTANCE_METERS ||
        nowMillis - lastCountedAtMillis >= RECOUNT_INTERVAL_MILLIS

    /** The quests of [quests] that are within [radiusMeters] of [position], counted, plus the
     *  nearest of them.
     *
     *  [quests] is what a bounding box query returned, so it reaches further than the radius in
     *  the corners - filtering by the real distance here is what makes the count a circle rather
     *  than a square. The distance is to [Quest.position], the one point a quest is pinned at,
     *  not to the nearest point of its geometry: a long street is "at" its middle here, the same
     *  as everywhere else in the app that talks about where a quest is. */
    fun nearbyQuests(
        quests: Collection<Quest>,
        position: LatLon,
        radiusMeters: Double = NEARBY_RADIUS_METERS,
    ): NearbyQuests {
        var count = 0
        var nearest: Quest? = null
        var nearestDistance = Double.MAX_VALUE
        for (quest in quests) {
            val distance = quest.position.distanceTo(position)
            if (distance > radiusMeters) continue
            count++
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = quest
            }
        }
        return NearbyQuests(
            count = count,
            nearest = nearest,
            nearestDistanceMeters = if (nearest != null) nearestDistance else 0.0,
        )
    }
}

/** How many quests are within reach, and which one is closest. */
data class NearbyQuests(
    val count: Int,
    val nearest: Quest?,
    /** meaningless when [nearest] is null */
    val nearestDistanceMeters: Double,
)
