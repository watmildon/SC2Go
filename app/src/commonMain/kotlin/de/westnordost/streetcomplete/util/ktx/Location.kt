package de.westnordost.streetcomplete.util.ktx

import de.westnordost.streetcomplete.data.location.Location
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.spatialk.units.International
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/** The origin [toLocation] measures [Location.elapsedDuration] from. Roughly process start - on
 *  Kotlin/Native a file's globals are initialised when the file is first used, so it is the first
 *  fix's conversion rather than the process start itself, and a fix taken before it (the location
 *  manager's cached one, replayed on start) reads negative. That is fine: only the order and the
 *  differences matter, and nothing compares it to zero. */
private val TIME_ORIGIN = TimeSource.Monotonic.markNow()

fun LocationEvent.Update.toLocation(): Location {
    val mark = measurementMark
    return Location(
        position = measurement.position.toLatLon(),
        accuracy = measurement.horizontalAccuracy?.toFloat(International.Meters) ?: 0f,
        /* How long after the origin the fix was taken - a monotonic clock reading, like the
           elapsedRealtimeNanos this stands in for on Android, and not the fix's age: RecentLocations
           orders and expires by it, so a later fix must always have a larger value. An age is the
           opposite, near zero for every fresh fix and shrinking as they get fresher, which had it
           reject nearly every fix as "not newer" and never expire any.

           The library declares measurementMark as a plain TimeMark, but both platforms hand over a
           mark on the monotonic clock (iOS: TimeSource.Monotonic.markNow() - ageAtReceipt()), which
           can be subtracted from the origin exactly. The fallback - upstream's own implementation -
           reads the clock twice and is off by the nanoseconds between the reads. */
        elapsedDuration =
            if (mark is ComparableTimeMark) mark - TIME_ORIGIN
            else TIME_ORIGIN.elapsedNow() - mark.elapsedNow(),
    )
}

fun org.maplibre.spatialk.geojson.Position.toLatLon(): LatLon =
    LatLon(latitude, longitude)

// TODO remove after upgrading to a version containing https://github.com/maplibre/maplibre-compose/pull/1393
@OptIn(ExperimentalCoroutinesApi::class)
fun LocationProvider.updatesWithPermissionChanges(request: LocationRequest): Flow<LocationEvent> =
    permission.flatMapLatest { updates(request) }
