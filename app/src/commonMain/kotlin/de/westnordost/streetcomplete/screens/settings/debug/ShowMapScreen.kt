package de.westnordost.streetcomplete.screens.settings.debug

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.AppBarDefaults
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.resources.quest_access_point
import de.westnordost.streetcomplete.resources.quest_apple
import de.westnordost.streetcomplete.resources.quest_artwork
import de.westnordost.streetcomplete.resources.quest_baby
import de.westnordost.streetcomplete.resources.quest_barrier
import de.westnordost.streetcomplete.resources.quest_beach
import de.westnordost.streetcomplete.resources.quest_bench_poi
import de.westnordost.streetcomplete.resources.quest_bicycle
import de.westnordost.streetcomplete.screens.main.map.BASE_STYLE
import de.westnordost.streetcomplete.screens.main.map.Light
import de.westnordost.streetcomplete.screens.main.map.MapColors
import de.westnordost.streetcomplete.screens.main.map.Night
import de.westnordost.streetcomplete.screens.main.map.MapStyle
import de.westnordost.streetcomplete.screens.main.map.layers.Pin
import de.westnordost.streetcomplete.screens.main.map.layers.PinsLayers
import de.westnordost.streetcomplete.ui.common.BackIcon
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/** Debug screen that shows the map with a handful of synthetic quest pins.
 *
 *  The pins are made up on purpose: it lets us see whether the map and in particular the pin icons
 *  render at all, without first having to log in and download data. */
@Composable
fun ShowMapScreen(
    onClickBack: () -> Unit,
) {
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(target = CENTER, zoom = 17.0)
    )

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Show map") },
            windowInsets = AppBarDefaults.topAppBarWindowInsets,
            navigationIcon = { IconButton(onClick = onClickBack) { BackIcon() } },
        )
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Json(BASE_STYLE),
            zoomRange = 0f..22f,
            cameraState = cameraState,
            options = MapOptions(ornamentOptions = OrnamentOptions.AllDisabled),
        ) {
            MapStyle(
                colors = if (isSystemInDarkTheme()) MapColors.Night else MapColors.Light,
                languages = listOf(Locale.current.language),
                aboveLabelsContent = {
                    PinsLayers(
                        pins = TEST_PINS,
                        onClickPin = {},
                        onZoomToCluster = {},
                    )
                },
            )
        }
    }
}

/** Alexanderplatz, Berlin - somewhere with enough going on to see the map render */
private val CENTER = Position(longitude = 13.4132, latitude = 52.5215)

private val TEST_PIN_ICONS = listOf(
    Res.drawable.quest_access_point,
    Res.drawable.quest_apple,
    Res.drawable.quest_artwork,
    Res.drawable.quest_baby,
    Res.drawable.quest_barrier,
    Res.drawable.quest_beach,
    Res.drawable.quest_bench_poi,
    Res.drawable.quest_bicycle,
)

private val TEST_PINS = TEST_PIN_ICONS.mapIndexed { index, icon ->
    Pin(
        position = LatLon(
            latitude = 52.5215 + (index / 4) * 0.0005,
            longitude = 13.4132 + (index % 4) * 0.0008,
        ),
        icon = icon,
        // PinsLayers requires non-null properties, it merges them into the GeoJSON feature
        properties = JsonObject(mapOf("index" to JsonPrimitive(index))),
    )
}
