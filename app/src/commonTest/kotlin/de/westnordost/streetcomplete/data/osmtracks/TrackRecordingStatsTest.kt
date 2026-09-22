package de.westnordost.streetcomplete.data.osmtracks

import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.ElementType
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osm.osmquests.OsmQuest
import de.westnordost.streetcomplete.data.osmtracks.TrackRecordingStats.NEARBY_RADIUS_METERS
import de.westnordost.streetcomplete.data.osmtracks.TrackRecordingStats.RECOUNT_DISTANCE_METERS
import de.westnordost.streetcomplete.data.osmtracks.TrackRecordingStats.RECOUNT_INTERVAL_MILLIS
import de.westnordost.streetcomplete.data.quest.Quest
import de.westnordost.streetcomplete.data.quest.TestQuestTypeA
import de.westnordost.streetcomplete.util.math.distanceTo
import de.westnordost.streetcomplete.util.math.translate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the Live Activity shows while a track is being recorded, minus the plumbing that feeds it */
class TrackRecordingStatsTest {

    private val origin = LatLon(53.0, 9.0)

    /* ------------------------------------- distance --------------------------------------- */

    @Test fun anEmptyTrackIsZeroLong() {
        assertEquals(0.0, TrackRecordingStats.totalDistanceMeters(emptyList()))
    }

    @Test fun aSinglePointIsZeroLong() {
        assertEquals(0.0, TrackRecordingStats.totalDistanceMeters(listOf(trackpoint(origin))))
    }

    @Test fun theLengthIsTheSumOfTheLegs() {
        val a = origin
        val b = origin.translate(30.0, 0.0)
        val c = b.translate(40.0, 90.0)
        val length = TrackRecordingStats.totalDistanceMeters(listOf(a, b, c).map { trackpoint(it) })
        assertTrue(abs(length - 70.0) < 0.5, "expected about 70m, was $length")
    }

    /** What the recorder does per fix has to come to the same thing as summing the whole list */
    @Test fun addingUpTheStepsIsTheSameAsTheWholeLength() {
        val positions = listOf(origin, origin.translate(12.0, 30.0), origin.translate(25.0, 200.0))
        val track = positions.map { trackpoint(it) }
        var accumulated = 0.0
        for ((i, point) in track.withIndex()) {
            accumulated += TrackRecordingStats.addedDistanceMeters(track.getOrNull(i - 1), point)
        }
        assertEquals(TrackRecordingStats.totalDistanceMeters(track), accumulated, 1e-9)
    }

    @Test fun theFirstPointAddsNothing() {
        assertEquals(0.0, TrackRecordingStats.addedDistanceMeters(null, trackpoint(origin)))
    }

    /* ----------------------------------- recount gating ----------------------------------- */

    @Test fun theFirstFixAlwaysCounts() {
        assertTrue(TrackRecordingStats.shouldRecountNearbyQuests(null, 0, origin, 0))
    }

    @Test fun standingStillDoesNotRecount() {
        assertFalse(TrackRecordingStats.shouldRecountNearbyQuests(
            lastCountedAt = origin,
            lastCountedAtMillis = 1000,
            position = origin.translate(1.0, 0.0),
            nowMillis = 1000 + RECOUNT_INTERVAL_MILLIS - 1,
        ))
    }

    @Test fun walkingFarEnoughRecounts() {
        assertTrue(TrackRecordingStats.shouldRecountNearbyQuests(
            lastCountedAt = origin,
            lastCountedAtMillis = 1000,
            position = origin.translate(RECOUNT_DISTANCE_METERS + 1.0, 0.0),
            nowMillis = 1001,
        ))
    }

    /** quests appear and disappear under a standing user too - a download, or a solved quest */
    @Test fun waitingLongEnoughRecountsEvenStandingStill() {
        assertTrue(TrackRecordingStats.shouldRecountNearbyQuests(
            lastCountedAt = origin,
            lastCountedAtMillis = 1000,
            position = origin,
            nowMillis = 1000 + RECOUNT_INTERVAL_MILLIS,
        ))
    }

    /* ----------------------------------- nearby quests ------------------------------------ */

    @Test fun noQuestsAtAll() {
        val nearby = TrackRecordingStats.nearbyQuests(emptyList(), origin)
        assertEquals(0, nearby.count)
        assertNull(nearby.nearest)
    }

    /** the bounding box the quests came from reaches further than the radius in its corners */
    @Test fun questsOutsideTheRadiusDoNotCount() {
        val inside = quest(origin.translate(NEARBY_RADIUS_METERS - 5.0, 0.0))
        val outside = quest(origin.translate(NEARBY_RADIUS_METERS + 5.0, 45.0))
        val nearby = TrackRecordingStats.nearbyQuests(listOf(inside, outside), origin)
        assertEquals(1, nearby.count)
        assertSame(inside, nearby.nearest)
    }

    @Test fun theNearestIsTheNearest() {
        val far = quest(origin.translate(40.0, 0.0))
        val near = quest(origin.translate(10.0, 180.0))
        val middling = quest(origin.translate(25.0, 90.0))
        val nearby = TrackRecordingStats.nearbyQuests(listOf(far, near, middling), origin)
        assertEquals(3, nearby.count)
        assertSame(near, nearby.nearest)
        assertTrue(
            abs(nearby.nearestDistanceMeters - 10.0) < 0.5,
            "expected about 10m, was ${nearby.nearestDistanceMeters}",
        )
    }

    @Test fun aQuestUnderfootCounts() {
        val here = quest(origin)
        val nearby = TrackRecordingStats.nearbyQuests(listOf(here), origin)
        assertEquals(1, nearby.count)
        assertEquals(0.0, nearby.nearestDistanceMeters, 0.001)
    }

    /** exactly on the line is inside it, so that a quest does not flicker in and out at the edge */
    @Test fun theRadiusIsInclusive() {
        val onTheLine = quest(origin.translate(NEARBY_RADIUS_METERS, 0.0))
        val nearby = TrackRecordingStats.nearbyQuests(
            listOf(onTheLine),
            origin,
            // the translation is not exact to the millimetre, so ask for exactly how far it came out
            radiusMeters = origin.distanceTo(onTheLine.position),
        )
        assertEquals(1, nearby.count)
    }

    private fun trackpoint(position: LatLon) = Trackpoint(position, 0, 0f, 0f)

    /* a different element id each time: two quests of the same type on the same element are the
       same quest, and the lists below are meant to hold several distinct ones */
    private var nextElementId = 1L

    private fun quest(position: LatLon): Quest =
        OsmQuest(TestQuestTypeA(), ElementType.NODE, nextElementId++, ElementPointGeometry(position))
}
