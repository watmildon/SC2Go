package de.westnordost.streetcomplete.screens.main.map.layers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.resources.map_pin_circle
import de.westnordost.streetcomplete.resources.quest_create_note
import de.westnordost.streetcomplete.screens.main.map.pinPainter
import de.westnordost.streetcomplete.screens.main.map.toGeometry
import de.westnordost.streetcomplete.ui.ktx.id
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.any
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToNumber
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.div
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.gt
import org.maplibre.compose.expressions.dsl.gte
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.log2
import org.maplibre.compose.expressions.dsl.lte
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.sp
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Geometry

/** Display pins on the map, e.g. quest pins or pins for recent edits */
@MaplibreComposable
@Composable
fun PinsLayers(
    pins: Collection<Pin>,
    onClickPin: (properties: JsonObject) -> Unit,
    onZoomToCluster: (targetZoom: Double) -> Unit,
    visible: Boolean = true,
) {
    val coroutineScope = rememberCoroutineScope()

    val pinIcons = remember(pins) { pins.map { it.icon }.distinct() }

    /* Keyed on the icon rather than positionally: pinIcons grows as new kinds of quest come into
       view while panning, and a positional remember would shift every slot after the new one and
       reload all of those painters. */
    val pinPainters = pinIcons.map { icon -> key(icon) { pinPainter(painterResource(icon)) } }
    val fallbackPainter = pinPainter(painterResource(Res.drawable.quest_create_note))

    /* Remembered on the icons, not rebuilt with every change to the pins. Panning changes the pins
       constantly while the set of icon *kinds* barely moves, and building this costs tens of
       milliseconds on the main thread - it is one case per distinct icon, which is what
       maplibre-compose#468 forces (see below). That was the panning stutter. */
    val iconImage = remember(pinIcons, pinPainters, fallbackPainter) {
        switch(
            feature["icon-image"].convertToString(),
            *pinIcons.mapIndexed { i, icon ->
                case("pin_" + icon.id, image(pinPainters[i]))
            }.toTypedArray(),
            fallback = image(fallbackPainter),
        )
    }

    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(FeatureCollection(pins.map { it.toGeoJsonFeature() })),
        options = GeoJsonOptions(
            cluster = true,
            clusterMaxZoom = CLUSTER_MAX_ZOOM,
            clusterRadius = 55
        )
    )

    fun onClickCluster(features: List<Feature<Geometry, JsonObject?>>): ClickResult {
        val feature = features.firstOrNull() ?: return ClickResult.Pass
        coroutineScope.launch {
            onZoomToCluster(source.getClusterExpansionZoom(feature))
        }
        return ClickResult.Consume
    }

    fun onClick(features: List<Feature<Geometry, JsonObject?>>): ClickResult {
        val properties = features.firstOrNull()?.properties ?: return ClickResult.Pass
        onClickPin(properties)
        return ClickResult.Consume
    }

    SymbolLayer(
        id = PIN_CLUSTER_LAYER,
        source = source,
        visible = visible,
        minZoom = CLUSTER_MIN_ZOOM.toFloat(),
        maxZoom = CLUSTER_MAX_ZOOM.toFloat(),
        filter = all(
            zoom() gte const(CLUSTER_MIN_ZOOM),
            zoom() lte const(CLUSTER_MAX_ZOOM),
            feature["point_count"].convertToNumber() gt const(1)
        ),
        iconImage = image(painterResource(Res.drawable.map_pin_circle)),
        iconSize = const(0.5f) + (log2(feature["point_count"].convertToNumber()) / const(10f)),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        textField = feature["point_count"].convertToString(),
        textSize = (const(15f) + (log2(feature["point_count"].convertToNumber()) / const(1.5f))).sp,
        textFont = const(listOf("Roboto Regular")),
        textOffset = offset(0.em, 0.1.em),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
        onClick = ::onClickCluster,
    )
    CircleLayer(
        id = "pin-dot-layer",
        source = source,
        visible = visible,
        minZoom = CLUSTER_MIN_ZOOM.toFloat(),
        filter = any(
            zoom() gt const(CLUSTER_MAX_ZOOM),
            all(
                zoom() gte const(CLUSTER_MIN_ZOOM),
                feature["point_count"].convertToNumber() lte const(1)
            )
        ),
        color = const(Color.White),
        radius = const(5.dp),
        strokeColor = const(Color(0xffaaaaaa)),
        strokeWidth = const(1.dp),
        translate = offset(0.dp, -8.dp), // so that it hides behind the pin
        translateAnchor = const(TranslateAnchor.Viewport),
    )
    SymbolLayer(
        id = PINS_LAYER,
        source = source,
        visible = visible,
        minZoom = CLUSTER_MAX_ZOOM.toFloat(),
        filter = zoom() gt const(CLUSTER_MAX_ZOOM),
        sortKey = feature["icon-order"].convertToNumber(),
        /* Ideally this would just be image(feature["icon-image"]), i.e. refer to the pin image by
           name. That only works for images already defined in the style JSON, though, and
           MapLibre-Compose exposes no API to add images to the style under a given name - see
           https://github.com/maplibre/maplibre-compose/issues/468 and #18.
           So instead, declare every pin icon that is currently displayed as its own case, which
           is what makes MapLibre-Compose load them. */
        iconImage = iconImage,
        // constant icon size because click area would become a bit too small and more
        // importantly, dynamic size per zoom + collision doesn't work together well, it
        // results in a lot of flickering.
        iconSize = const(1f),
        /* TODO maplibre-compose: negative paddings not allowed
           https://github.com/maplibre/maplibre-compose/issues/1091
        iconPadding = const(PaddingValues.Absolute(
            left = 2.5.dp,
            top = -2.5.dp,
            right = 0.dp,
            bottom = -7.dp,
        )),*/
        iconOffset = const(DpOffset((-4.5).dp, (-34.5).dp)),
        iconAllowOverlap = const(false),
        iconIgnorePlacement = const(false),
        onClick = ::onClick,
    )
}

const val PIN_CLUSTER_LAYER = "pin-cluster-layer"
const val PINS_LAYER = "pins-layer"

/** Ids of the layers drawn by [PinsLayers] that handle clicks themselves */
val PINS_CLICKABLE_LAYERS = setOf(PIN_CLUSTER_LAYER, PINS_LAYER)

private const val CLUSTER_MIN_ZOOM = 13
private const val CLUSTER_MAX_ZOOM = 14

data class Pin(
    val position: LatLon,
    val icon: DrawableResource,
    val properties: JsonObject? = null,
    val order: Int = 0
)

private fun Pin.toGeoJsonFeature() =
    Feature(
        geometry = position.toGeometry(),
        /* must be a JsonObject and not just any Map: the GeoJSON serializer looks up the
           serializer by the runtime class, and a plain Map has none registered */
        properties = JsonObject(
            mapOf(
                "icon-image" to JsonPrimitive("pin_" + icon.id),
                "icon-order" to JsonPrimitive(order + 50),
            )
            + (properties ?: emptyMap())
        )
    )
