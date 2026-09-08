package de.westnordost.streetcomplete.data.osmtracks

import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/* The three scenarios the rule exists to tell apart. Positions are ~0.001° of latitude apart,
   about 110 m, so "far" is comfortably over the 50 m threshold and "near" is the same point. */
class TrackGapTest {

    private val here = LatLon(47.69, -122.09)
    private val farAway = LatLon(47.691, -122.09)
    /** ~20 m off: what a GPS delivers for the same spot after standing still for a while */
    private val jitteredHere = LatLon(47.69018, -122.09)
    private val t0 = 1_000_000L
    private val afterAMinute = t0 + 61_000L

    private fun point(at: LatLon, time: Long) = Trackpoint(at, time, 5f, 0f)

    @Test fun aStationaryStopOfAnyLengthNeverSplits() {
        // the low-power filter delivers nothing while standing still; the next fix is the same spot
        assertFalse(isTrackGap(point(here, t0), now = afterAMinute, position = here, recording = false))
        assertFalse(isTrackGap(point(here, t0), now = t0 + 3_600_000L, position = here, recording = false))
        // and the fix after the stop is never exactly the same spot: this is the case the threshold exists for
        assertFalse(isTrackGap(point(here, t0), now = afterAMinute, position = jitteredHere, recording = false))
    }

    @Test fun aLongGapSomewhereElseSplits() {
        // a tunnel, or backgrounded and walked away
        assertTrue(isTrackGap(point(here, t0), now = afterAMinute, position = farAway, recording = false))
    }

    @Test fun movingFarQuicklyIsNotAGap() {
        // distance alone is not enough: a fast walker is still one continuous track
        assertFalse(isTrackGap(point(here, t0), now = t0 + 10_000L, position = farAway, recording = false))
    }

    @Test fun neverSplitsWhileRecording() {
        assertFalse(isTrackGap(point(here, t0), now = afterAMinute, position = farAway, recording = true))
    }

    @Test fun theFirstFixIsNeverAGap() {
        assertFalse(isTrackGap(null, now = afterAMinute, position = farAway, recording = false))
    }
}
