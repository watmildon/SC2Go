package de.westnordost.streetcomplete.screens.main.map.layers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import de.westnordost.streetcomplete.screens.main.map.MapPerf
import de.westnordost.streetcomplete.screens.main.map.pinPainter
import de.westnordost.streetcomplete.screens.main.map.toGeometry
import de.westnordost.streetcomplete.ui.ktx.id
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ImageValue
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
    /** Built once by [PinIconImage] over every icon there can be, rather than here from whatever is
     *  in view. When null this falls back to building it per icon set, which is what it used to do
     *  and what the measurement compares against. */
    iconImage: Expression<ImageValue>? = null,
    /** Built off the main thread from the same pins. When null it is built here instead. */
    prebuiltFeatures: FeatureCollection<Geometry, JsonObject?>? = null,
) {
    val coroutineScope = rememberCoroutineScope()

    /* Every icon that has been on screen at any point, not only the ones on screen now.
       Resolving a pin painter decodes a vector drawable and costs about a millisecond; dropping it
       the moment its quest type pans out of view means paying that again the moment it pans back,
       which while panning is constantly. Keeping them all composed trades memory - one rasterised
       pin per quest type in MapLibre's atlas - for never decoding the same icon twice.

       MainMap already relies on the same property one level up, where it hides the pin layers
       rather than removing them so that opening a form does not re-decode every icon. */
    val knownIcons = remember { mutableListOf<DrawableResource>() }
    val pinIcons = remember(pins) {
        val mark = MapPerf.mark()
        val inView = pins.map { it.icon }.distinct()
        val added = inView.filterNot { it in knownIcons }
        knownIcons.addAll(added)
        MapPerf.logSince(mark) {
            "distinct icons: ${inView.size} in view of ${pins.size} pins" +
            ", known ${knownIcons.size}" + (if (added.isNotEmpty()) ", ${added.size} new" else "")
        }
        /* A fresh list, but an equal one whenever nothing was added - so the expression and the
           painters below, which are remembered on it, are only rebuilt when the set really grew. */
        if (MapPerf.keepIconsLive) knownIcons.toList() else inView
    }

    /* Logged separately from the rebuild below because this is its *cause*: the expression and the
       images are only rebuilt when this set grows, so knowing which icons arrived says which quest
       type coming into view was worth the work. Once it has stopped growing, panning is free. */
    val previousIcons = remember { mutableStateOf(emptyList<DrawableResource>()) }
    if (pinIcons != previousIcons.value) {
        val added = pinIcons - previousIcons.value.toSet()
        MapPerf.log {
            "known icon set grew: ${previousIcons.value.size} -> ${pinIcons.size}" +
            (if (added.isNotEmpty()) ", added " + added.joinToString(",") { it.id.orEmpty() } else "")
        }
        previousIcons.value = pinIcons
    }

    /* Keyed on the icon rather than positionally: pinIcons grows as new kinds of quest come into
       view while panning, and a positional remember would shift every slot after the new one and
       reload all of those painters. */
    val paintersMark = MapPerf.mark()
    val pinPainters = pinIcons.map { icon -> key(icon) { pinPainter(painterResource(icon)) } }
    val fallbackPainter = pinPainter(painterResource(Res.drawable.quest_create_note))
    MapPerf.logSince(paintersMark) { "resolve ${pinPainters.size} pin painters" }

    /* Remembered on the icons, not rebuilt with every change to the pins. Panning changes the pins
       constantly while the set of icon *kinds* barely moves, and building this costs tens of
       milliseconds on the main thread - it is one case per distinct icon, which is what
       maplibre-compose#468 forces (see below). That was the panning stutter. */
    val localIconImage = remember(pinIcons, pinPainters, fallbackPainter) {
        val mark = MapPerf.mark()
        val expression = switch(
            feature["icon-image"].convertToString(),
            *pinIcons.mapIndexed { i, icon ->
                case("pin_" + icon.id, image(pinPainters[i]))
            }.toTypedArray(),
            fallback = image(fallbackPainter),
        )
        MapPerf.logSince(mark) { "BUILD ICON EXPRESSION for ${pinIcons.size} icons" }
        expression
    }
    val effectiveIconImage = iconImage ?: localIconImage

    val features = prebuiltFeatures ?: run {
        val featuresMark = MapPerf.mark()
        val built = FeatureCollection(pins.map { it.toGeoJsonFeature() })
        MapPerf.logSince(featuresMark) { "build ${pins.size} GeoJSON features" }
        built
    }

    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(features),
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
        iconImage = effectiveIconImage,
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

fun Pin.toGeoJsonFeature() =
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

/** Resolves every pin icon and builds the icon expression from them, once.
 *
 *  Panning does not change which icons *can* appear, only which ones happen to be on screen, so
 *  there is no reason to rebuild any of this while panning — and rebuilding it is what made panning
 *  cost anything. Given every icon up front, the painters are resolved once and the `switch` is
 *  built once, and [PinsLayers] is handed the result.
 *
 *  It returns Unit and takes a list and a state holder that do not change, which makes it
 *  skippable: it composes on the first pass and is skipped from then on, so the walk over the icons
 *  happens exactly once no matter how much the pins change. Publishing through [output] rather than
 *  returning the expression is what buys that — a composable that returns a value cannot be
 *  skipped. */
@Composable
fun PinIconImage(
    icons: List<DrawableResource>,
    output: MutableState<Expression<ImageValue>?>,
) {
    val mark = MapPerf.mark()
    val painters = icons.map { icon -> key(icon) { pinPainter(painterResource(icon)) } }
    val fallbackPainter = pinPainter(painterResource(Res.drawable.quest_create_note))
    val expression = remember(painters, fallbackPainter) {
        switch(
            feature["icon-image"].convertToString(),
            *icons.mapIndexed { i, icon ->
                case("pin_" + icon.id, image(painters[i]))
            }.toTypedArray(),
            fallback = image(fallbackPainter),
        )
    }
    MapPerf.logSince(mark) { "HOIST: resolve+build for ${icons.size} icons" }
    SideEffect { output.value = expression }
}

/** The pins as GeoJSON, built off the main thread.
 *
 *  Turning a pin into a feature allocates a JsonObject each, so it is linear in the number of pins
 *  and was costing tens of milliseconds during composition with a few thousand of them in view.
 *  None of it needs the main thread.
 *
 *  The result arrives a frame after the pins do, which is the price: the map draws the previous set
 *  for one more frame rather than blocking to catch up. Returns null until the first set is ready,
 *  which [PinsLayers] reads as "build it yourself". */
@Composable
fun pinFeatures(pins: Collection<Pin>): FeatureCollection<Geometry, JsonObject?>? {
    if (!MapPerf.offThreadGeoJson) return null
    return produceState<FeatureCollection<Geometry, JsonObject?>?>(null, pins) {
        value = withContext(Dispatchers.Default) {
            val mark = MapPerf.mark()
            val features = FeatureCollection(pins.map { it.toGeoJsonFeature() })
            MapPerf.logSince(mark) { "OFF-THREAD build ${pins.size} GeoJSON features" }
            features
        }
    }.value
}
