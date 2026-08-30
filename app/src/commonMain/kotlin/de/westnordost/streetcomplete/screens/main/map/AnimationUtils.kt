package de.westnordost.streetcomplete.screens.main.map

import androidx.compose.runtime.State
import androidx.compose.animation.core.Spring.StiffnessLow
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.util.math.normalizeLongitude

@Composable
fun animateLatLonAsState(
    targetValue: LatLon,
    animationSpec: SpringSpec<LatLon> = spring(stiffness = StiffnessLow),
    label: String = "LatLonAnimation"
): State<LatLon> {
    var targetLongitude by remember { mutableStateOf(targetValue.longitude) }

    LaunchedEffect(targetValue.longitude) {
        targetLongitude += normalizeLongitude(targetValue.longitude - targetLongitude)
    }

    val intAnimationSpec = spring(
        dampingRatio = animationSpec.dampingRatio,
        stiffness = animationSpec.stiffness,
        visibilityThreshold = 1
    )

    val animatedLongitude by animateIntAsState(
        targetValue = (targetLongitude * FIXED_POINT_SCALE).toInt(),
        animationSpec = intAnimationSpec,
        label = label+"-Lon"
    )
    val animatedLatitude by animateIntAsState(
        targetValue = (targetValue.latitude * FIXED_POINT_SCALE).toInt(),
        animationSpec = intAnimationSpec,
        label = label+"-Lat"
    )

    return remember { derivedStateOf { LatLon(
        latitude = animatedLatitude / FIXED_POINT_SCALE,
        longitude = normalizeLongitude(animatedLongitude / FIXED_POINT_SCALE)
    ) } }
}

/** Coordinates are animated as fixed point integers, there being no animation for a LatLon.
 *
 *  At this scale the animated position is precise to about 11cm, far finer than any GPS fix, and
 *  a whole turn around the globe stays well inside Int range: 180 * 1e6 = 1.8e8 against a limit of
 *  about 2.1e9. It is also what makes visibilityThreshold = 1 above mean "settled to 11cm". */
private const val FIXED_POINT_SCALE = 1e6
