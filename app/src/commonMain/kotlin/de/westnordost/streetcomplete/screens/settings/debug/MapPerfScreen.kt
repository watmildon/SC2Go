package de.westnordost.streetcomplete.screens.settings.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material.AppBarDefaults
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.quest.QuestTypeRegistry
import de.westnordost.streetcomplete.screens.main.map.BASE_STYLE
import de.westnordost.streetcomplete.screens.main.map.FrameStutterLogger
import de.westnordost.streetcomplete.screens.main.map.MainThreadStallLogger
import de.westnordost.streetcomplete.screens.main.map.Light
import de.westnordost.streetcomplete.screens.main.map.MapColors
import de.westnordost.streetcomplete.screens.main.map.MapPerf
import de.westnordost.streetcomplete.screens.main.map.MapStyle
import de.westnordost.streetcomplete.screens.main.map.layers.PinIconImage
import de.westnordost.streetcomplete.screens.main.map.Night
import de.westnordost.streetcomplete.screens.main.map.layers.Pin
import de.westnordost.streetcomplete.screens.main.map.layers.PinsLayers
import de.westnordost.streetcomplete.screens.main.map.layers.pinFeatures
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ImageValue
import de.westnordost.streetcomplete.screens.main.map.toBoundingBox
import de.westnordost.streetcomplete.ui.common.BackIcon
import de.westnordost.streetcomplete.util.logs.Log
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.DrawableResource
import org.koin.compose.koinInject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/** A stress test for panning over quest pins.
 *
 *  Panning over real data stutters when the *set of icons* on screen changes, which is hard to
 *  reproduce deliberately: it needs an area with enough variety of quest types, and it needs the
 *  data to be downloaded first. This makes an area like that up, so the same pan can be run over
 *  and over and the numbers compared.
 *
 *  It drives the camera itself rather than waiting to be panned by hand, for two reasons: the pan
 *  is then identical between runs, so two measurements can be compared; and it can be run on the
 *  simulator, which has no way to be told to swipe.
 *
 *  Reach it without tapping:
 *  `xcrun simctl launch booted de.westnordost.streetcomplete -screen MapPerf`
 *
 *  What to read afterwards - the app's own log, filtered to the MapPerf tag. Look for LONG FRAME
 *  lines next to "BUILD ICON EXPRESSION": that adjacency is the whole hypothesis. */
@Composable
fun MapPerfScreen(
    onClickBack: () -> Unit,
    questTypeRegistry: QuestTypeRegistry = koinInject(),
) {
    // during composition, not in a LaunchedEffect: that runs after the first composition, by which
    // point the things being measured have already happened once, unmeasured
    remember { MapPerf.enabled = true }

    /* The real icon pool, not a hand-written list: the point is to be representative of what the
       app actually has to rasterise, and there are far more quest types than anyone would list. */
    val iconPool = remember(questTypeRegistry) {
        questTypeRegistry.map { it.icon }.distinct()
    }
    val data = remember(iconPool) { FakeQuestData(iconPool) }

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(target = FakeQuestData.START, zoom = 17.0)
    )
    var visiblePins by remember { mutableStateOf<List<Pin>>(emptyList()) }
    val hoistedIconImage = remember { mutableStateOf<Expression<ImageValue>?>(null) }
    val features = pinFeatures(visiblePins)
    var legLabel by remember { mutableStateOf("starting") }

    LaunchedEffect(Unit) {
        Log.i(MapPerf.TAG, "=== MapPerf: ${data.pins.size} pins, ${iconPool.size} distinct icons in the pool ===")
    }

    // recompute what is in view as the camera moves, the way MapQuestPinsSource does for real data
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.position }
            .distinctUntilChanged()
            .collect {
                val bbox = cameraState.viewport?.visibleBoundingBox?.toBoundingBox() ?: return@collect
                visiblePins = data.pinsIn(bbox)
            }
    }

    // the scripted pan
    LaunchedEffect(Unit) {
        for (pass in 1..PASSES) {
            for ((index, leg) in FakeQuestData.PAN_LEGS.withIndex()) {
                legLabel = "pass $pass, leg ${index + 1}/${FakeQuestData.PAN_LEGS.size}"
                Log.i(MapPerf.TAG, "--- $legLabel -> ${leg.latitude},${leg.longitude} ---")
                cameraState.animateTo(
                    cameraState.position.copy(
                        target = Position(longitude = leg.longitude, latitude = leg.latitude),
                        zoom = 17.0,
                    ),
                    LEG_DURATION,
                )
            }
        }
        legLabel = "done"
        Log.i(MapPerf.TAG, "=== MapPerf: pan finished ===")
    }

    MainThreadStallLogger()
    FrameStutterLogger()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Map perf") },
            windowInsets = AppBarDefaults.topAppBarWindowInsets,
            navigationIcon = { IconButton(onClick = onClickBack) { BackIcon() } },
        )
        Box(Modifier.fillMaxSize()) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                baseStyle = BaseStyle.Json(BASE_STYLE),
                zoomRange = 0f..22f,
                cameraState = cameraState,
            ) {
                val colors = if (MaterialTheme.colors.isLight) MapColors.Light else MapColors.Night
                MapStyle(
                    colors = colors,
                    languages = listOf(Locale.current.language),
                    aboveLabelsContent = {
                        // before the layers, and given a list that never changes, so it composes once
                        PinIconImage(iconPool, hoistedIconImage)
                        PinsLayers(
                            pins = visiblePins,
                            onClickPin = {},
                            onZoomToCluster = {},
                            iconImage = hoistedIconImage.value.takeIf { MapPerf.hoistIconExpression },
                            prebuiltFeatures = features,
                        )
                    },
                )
            }
            Text(
                text = "$legLabel · ${visiblePins.size} pins · " +
                    "${visiblePins.map { it.icon }.distinct().size} icons",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .safeContentPadding()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                color = Color.White,
                fontSize = 12.sp,
            )
        }
    }
}

private const val PASSES = 2
private val LEG_DURATION = 4000.milliseconds

/** Made-up quest pins, clustered and varied, over an area big enough to pan across.
 *
 *  Two properties matter for what this is measuring, and both are deliberate:
 *
 *  - pins come in **clusters** rather than spread evenly, because real quests do, and a cluster is
 *    what puts hundreds of pins on screen at once;
 *  - each cluster draws its icons from a small, *different* subset of the pool, because it is the
 *    icon set changing that triggers the expensive rebuild. Spread the icons evenly over the area
 *    instead and every view would contain every icon, the set would never change, and the thing
 *    under test would never happen. */
class FakeQuestData(
    iconPool: List<DrawableResource>,
    clusterCount: Int = 260,
    pinsPerCluster: Int = 80,
    seed: Int = 20260831,
) {
    val pins: List<Pin>

    /** pins bucketed by cell, so panning does not spend its time scanning the whole list */
    private val byCell: Map<Long, List<Pin>>

    init {
        val random = Random(seed)
        val built = ArrayList<Pin>(clusterCount * pinsPerCluster)
        for (c in 0 until clusterCount) {
            val centerLat = START.latitude + (random.nextDouble() - 0.5) * SPAN_LAT
            val centerLon = START.longitude + (random.nextDouble() - 0.5) * SPAN_LON
            // 6-14 icon kinds per cluster, out of the whole pool
            val kinds = 6 + random.nextInt(9)
            val clusterIcons = List(kinds) { iconPool[random.nextInt(iconPool.size)] }
            for (p in 0 until pinsPerCluster) {
                // a tight scatter, so a cluster is a place rather than a smear
                val lat = centerLat + (random.nextDouble() - 0.5) * CLUSTER_SPAN
                val lon = centerLon + (random.nextDouble() - 0.5) * CLUSTER_SPAN * 1.6
                built.add(Pin(
                    position = LatLon(lat, lon),
                    icon = clusterIcons[random.nextInt(clusterIcons.size)],
                    properties = JsonObject(mapOf("i" to JsonPrimitive(built.size))),
                    order = random.nextInt(100),
                ))
            }
        }
        pins = built
        byCell = built.groupBy { cellOf(it.position.latitude, it.position.longitude) }
    }

    fun pinsIn(bbox: BoundingBox): List<Pin> {
        val result = ArrayList<Pin>()
        val minY = (bbox.min.latitude / CELL).toInt() - 1
        val maxY = (bbox.max.latitude / CELL).toInt() + 1
        val minX = (bbox.min.longitude / CELL).toInt() - 1
        val maxX = (bbox.max.longitude / CELL).toInt() + 1
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val cell = byCell[key(x, y)] ?: continue
                for (pin in cell) {
                    if (pin.position.latitude in bbox.min.latitude..bbox.max.latitude &&
                        pin.position.longitude in bbox.min.longitude..bbox.max.longitude
                    ) {
                        result.add(pin)
                    }
                }
            }
        }
        return result
    }

    private fun cellOf(lat: Double, lon: Double): Long =
        key((lon / CELL).toInt(), (lat / CELL).toInt())

    companion object {
        /** Alexanderplatz, Berlin — the same place the other debug map screen uses */
        val START = Position(longitude = 13.4132, latitude = 52.5215)

        private const val SPAN_LAT = 0.028
        private const val SPAN_LON = 0.046
        private const val CLUSTER_SPAN = 0.0016
        private const val CELL = 0.002

        private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

        /** A pan that crosses clusters rather than sitting still, so the icon set keeps changing.
         *  Deliberately a fixed path: two runs are only comparable if they cover the same ground. */
        val PAN_LEGS: List<LatLon> = buildList {
            val steps = 8
            for (i in 0 until steps) {
                val t = i.toDouble() / (steps - 1)
                // a zigzag across the area, so each leg enters clusters the last one did not
                add(LatLon(
                    latitude = START.latitude + (t - 0.5) * SPAN_LAT * 0.8,
                    longitude = START.longitude + ((if (i % 2 == 0) -1 else 1) * 0.35) * SPAN_LON,
                ))
            }
        }
    }
}
