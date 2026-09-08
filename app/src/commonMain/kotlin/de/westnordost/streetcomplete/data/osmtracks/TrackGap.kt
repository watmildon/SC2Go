package de.westnordost.streetcomplete.data.osmtracks

import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.util.math.distanceTo

/** Whether a new fix should start a new track rather than extend the current one.
 *
 *  A gap is a stretch of the survey the track says nothing about, so drawing across it, or taking
 *  a travel bearing across it, would show the user somewhere they did not walk. Two things have to
 *  be true for that, and the second was added with the low-power mode's 10 m distance filter:
 *
 *  - **Long enough**: more than [maxTimeBetweenLocations] since the last point. Alone this was the
 *    original rule, and alone it is wrong under a distance filter - a user standing still is
 *    delivered nothing, so every form that took a minute split the track.
 *  - **Somewhere else**: the new fix is more than [minDistance] from the last point. A stationary
 *    stop of any length then never splits; a tunnel, or backgrounding the app and walking away,
 *    still does.
 *
 *  Never while [recording]: a recorded track is being attached to a note, and cutting it would
 *  throw away everything before the gap.
 *
 *  Pure so it can be tested; the composable that keeps the track calls it with its own state. */
internal fun isTrackGap(
    last: Trackpoint?,
    now: Long,
    position: LatLon,
    recording: Boolean,
    maxTimeBetweenLocations: Long = TRACK_GAP_MAX_TIME,
    minDistance: Double = TRACK_GAP_MIN_DISTANCE,
): Boolean {
    if (last == null || recording) return false
    return now - last.time > maxTimeBetweenLocations &&
        last.position.distanceTo(position) > minDistance
}

/** A longer gap than this between fixes is a candidate for starting the track over, as on Android */
internal const val TRACK_GAP_MAX_TIME = 60L * 1000

/** How far apart, in metres, two fixes either side of a long gap have to be for it to count as a
 *  real gap rather than a stationary stop. Wide enough that GPS jitter while standing still does
 *  not qualify; narrow enough that walking away for a minute does. */
internal const val TRACK_GAP_MIN_DISTANCE = 50.0
