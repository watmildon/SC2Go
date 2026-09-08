package de.westnordost.streetcomplete.util.ktx

import de.westnordost.streetcomplete.data.location.Location
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import org.maplibre.spatialk.units.International
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/** The origin [toLocation] measures [Location.elapsedDuration] from. Roughly process start - on
 *  Kotlin/Native a file's globals are initialised when the file is first used, so it is the first
 *  fix's conversion rather than the process start itself, and a fix taken before it (the location
 *  manager's cached one, replayed on start) reads negative. That is fine: only the order and the
 *  differences matter, and nothing compares it to zero. */
private val TIME_ORIGIN = TimeSource.Monotonic.markNow()

fun org.maplibre.compose.location.Location.toLocation(): Location {
    val timestamp = timestamp
    return Location(
        position = position.value.toLatLon(),
        accuracy = position.accuracy?.toFloat(International.Meters) ?: 0f,
        /* How long after the origin the fix was taken - a monotonic clock reading, like the
           elapsedRealtimeNanos this stands in for on Android, and not the fix's age: RecentLocations
           orders and expires by it, so a later fix must always have a larger value. An age is the
           opposite, near zero for every fresh fix and shrinking as they get fresher, which had it
           reject nearly every fix as "not newer" and never expire any.

           The library declares the timestamp as a plain TimeMark, but both platforms hand over a
           mark on the monotonic clock, which can be subtracted from the origin exactly. The
           fallback reads the clock twice and is off by the nanoseconds between the reads. */
        elapsedDuration =
            if (timestamp is ComparableTimeMark) timestamp - TIME_ORIGIN
            else TIME_ORIGIN.elapsedNow() - timestamp.elapsedNow(),
    )
}

fun org.maplibre.spatialk.geojson.Position.toLatLon(): LatLon =
    LatLon(latitude, longitude)
