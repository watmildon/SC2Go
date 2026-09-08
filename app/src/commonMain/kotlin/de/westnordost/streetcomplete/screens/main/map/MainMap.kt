package de.westnordost.streetcomplete.screens.main.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import de.westnordost.streetcomplete.data.AllEditTypes
import de.westnordost.streetcomplete.data.edithistory.EditKey
import de.westnordost.streetcomplete.data.location.Location
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.quest.QuestKey
import de.westnordost.streetcomplete.data.quest.QuestTypeRegistry
import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.resources.quest_create_note
import de.westnordost.streetcomplete.resources.quest_notes
import de.westnordost.streetcomplete.screens.main.ShownBottomSheet
import de.westnordost.streetcomplete.screens.main.map.layers.CurrentLocationLayers
import de.westnordost.streetcomplete.screens.main.map.layers.DownloadedAreaLayer
import de.westnordost.streetcomplete.screens.main.map.layers.FocusedGeometryLayers
import de.westnordost.streetcomplete.screens.main.map.layers.GeometryMarkersLayers
import de.westnordost.streetcomplete.screens.main.map.layers.Marker
import de.westnordost.streetcomplete.screens.main.map.layers.PINS_CLICKABLE_LAYERS
import de.westnordost.streetcomplete.screens.main.map.layers.PinIconImage
import de.westnordost.streetcomplete.screens.main.map.layers.PinsLayers
import de.westnordost.streetcomplete.screens.main.map.layers.STYLEABLE_OVERLAY_CLICKABLE_LAYERS
import de.westnordost.streetcomplete.screens.main.map.layers.SelectedPinsLayer
import de.westnordost.streetcomplete.screens.main.map.layers.StyleableOverlayLabelLayer
import de.westnordost.streetcomplete.screens.main.map.layers.StyleableOverlayLayers
import de.westnordost.streetcomplete.screens.main.map.layers.StyleableOverlaySideLayer
import de.westnordost.streetcomplete.screens.main.map.layers.TracksLayers
import de.westnordost.streetcomplete.screens.main.map.layers.overlayIcons
import de.westnordost.streetcomplete.screens.main.map.layers.pinFeatures
import de.westnordost.streetcomplete.screens.main.map.layers.toGeoJsonFeatures
import de.westnordost.streetcomplete.util.logs.Log
import de.westnordost.streetcomplete.util.math.distanceTo
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.rememberStyleState
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/**
 * MapLibre Map with StreetComplete theme and all the StreetComplete specific things displayed on
 * top.
 *
 * @param shownBottomSheet the bottom sheet currently shown. Depending on which bottom sheet is
 * shown, certain elements will be highlighted or hidden.
 *
 * @param shownMarkers geometry markers currently shown, such as nearby shops when the user has a
 * quest form that asks something about shops open. This is usually tied to the quest, but the quest
 * form can (split way, level of place quest, …) change what markers are shown, so this is decoupled
 * from [shownBottomSheet]
 *
 * @param isShowingUndoHistorySidebar whether the undo history sidebar is open. The overlay is
 * hidden when it is open.
 *
 * @param isOnScreen whether the map can actually be seen, i.e. nothing is drawn over the whole of
 * it. While it cannot, the quest pins and the overlay stop keeping themselves up to date. The map
 * itself carries on rendering, and the edit history pins are not stopped.
 *
 * @param onClickMap called when the user clicked the map itself, i.e. not any pin, overlay element
 * or other thing drawn on top of it. [clickAreaSizeInMeters] is how much ground the user's finger
 * covered, so that what was clicked "near enough" can be worked out.
 *
 * @param trackpoints where the user has been since the last break in reception, and
 * [oldTrackpointsLists] the stretches before that. Drawn so the user can see where they have
 * already been. [isRecordingTracks] draws the current one differently, as it is being recorded to
 * attach to a note.
 * */
@Composable
fun MainMap(
    onClickOverlayElement: (ElementKey) -> Unit,
    onClickQuest: (QuestKey) -> Unit,
    onClickEdit: (EditKey) -> Unit,
    location: Location?,
    rotation: () -> Float?,
    shownBottomSheet: ShownBottomSheet?,
    shownMarkers: Collection<Marker>?,
    isShowingUndoHistorySidebar: Boolean,
    trackpoints: List<LatLon>,
    oldTrackpointsLists: List<List<LatLon>>,
    isRecordingTracks: Boolean,
    modifier: Modifier = Modifier,
    onClickMap: (position: LatLon, clickAreaSizeInMeters: Double) -> Unit = { _, _ -> },
    onMapLongClick: MapClickHandler = { _, _ -> ClickResult.Pass },
    isOnScreen: Boolean = true,
    viewModel: MainMapViewModel = koinViewModel(),
    cameraState: CameraState = rememberCameraState(),
    styleState: StyleState = rememberStyleState(),
) {
    val coroutineScope = rememberCoroutineScope()

    val downloadedTiles by viewModel.downloadedTiles.collectAsState()
    val editHistoryPins by viewModel.editHistoryPins.collectAsState()
    val styledElements by viewModel.styleableElements.collectAsState()
    val questPins by viewModel.questPins.collectAsState()

    /* Turning pins into GeoJSON is linear in the number of pins and was being done during
       composition, on the main thread, every time the pins changed - tens of milliseconds with a
       few thousand pins in view. Nothing about it needs the main thread. */
    val questFeatures = pinFeatures(questPins)
    val editHistoryFeatures = pinFeatures(editHistoryPins)

    // because quests highlight additional information and history sidebar should feel clean
    val showOverlay = shownBottomSheet !is ShownBottomSheet.OsmQuest &&
        shownBottomSheet !is ShownBottomSheet.OsmNoteQuest &&
        !isShowingUndoHistorySidebar

    /* While a form is about something on the map, only that thing is shown - everything else would
       just be in the way of looking at it. What the form is about is drawn by SelectedPinsLayer
       below, so this hides the rest. The note form is the exception: it is not about anything that
       is on the map yet. Android does the same in hideNonHighlightedPins. */
    val showQuestPins = shownBottomSheet == null || shownBottomSheet is ShownBottomSheet.CreateOsmNote

    val selectedQuest = when (shownBottomSheet) {
        is ShownBottomSheet.OsmNoteQuest -> shownBottomSheet.quest
        is ShownBottomSheet.OsmQuest -> shownBottomSheet.quest
        else -> null
    }

    /* The big pin marking what the form is about, which is the other half of hiding the rest of
       them. Android highlights the overlay's own icon at the element for an overlay form, which
       is not a quest and so is not covered by selectedQuest. */
    val selectedPin = when {
        selectedQuest != null -> selectedQuest.type.icon to selectedQuest.markerLocations
        shownBottomSheet is ShownBottomSheet.Overlay && shownBottomSheet.geometry != null ->
            shownBottomSheet.overlay.icon to listOf(shownBottomSheet.geometry.center)
        else -> null
    }

    LaunchedEffect(cameraState.position) {
        viewModel.onMapMoved(cameraState)
    }

    /* The map stays composed behind the screens drawn on top of it, so that coming back to it does
       not mean building it all over again. It should not be keeping itself up to date while it is
       back there though - that is a database query and a rebuild of every pin in view for a map
       nobody can see, on every change to what is visible. */
    LaunchedEffect(isOnScreen) { viewModel.setOnScreen(isOnScreen) }

    fun zoomToCluster(targetZoom: Double) {
        coroutineScope.launch {
            cameraState.animateTo(cameraState.position.copy(zoom = targetZoom))
        }
    }

    /* MapLibre Compose calls onMapClick for every click, synchronously and before it has looked
       at whether anything drawn on the map was hit - that is done afterwards, in a coroutine,
       because querying the rendered features suspends. What is wanted here is Android's
       behaviour: the map itself was clicked, i.e. nothing on it was. So ask the same question
       here rather than trying to find out after the fact, which cannot be done without racing
       the library's own query. */
    fun onClickMapAt(position: Position, offset: DpOffset) {
        coroutineScope.launch {
            val radius = CLICK_AREA_SIZE.value.dp / 2
            val clickArea = DpRect(
                left = offset.x - radius,
                top = offset.y - radius,
                right = offset.x + radius,
                bottom = offset.y + radius,
            )
            if (cameraState.queryRenderedFeatures(clickArea, CLICKABLE_LAYERS).isNotEmpty()) return@launch

            /* how much ground the finger covered, the same way MainMapFragment.onClickMap
               measures it: the distance to where the edge of the finger is */
            val latLon = position.toLatLon()
            val edge = cameraState.screenLocationFromPosition(position)
                ?.let { cameraState.positionFromScreenLocation(DpOffset(it.x + radius, it.y)) }
            onClickMap(latLon, edge?.toLatLon()?.let { latLon.distanceTo(it) } ?: 0.0)
        }
    }

    /* The edge strips are siblings placed *after* the map, not a guard wrapped around it.
       Two earlier attempts consumed the touch on PointerEventPass.Initial and failed, because
       maplibre-compose never looks at consumption: MapInput.kt collects awaitPointerEvent() on the
       Main pass and acts on every event it is handed, with no isConsumed check anywhere in the
       file. Consumption is only a flag; every hit node still gets every event.

       Hit testing is the lever that does work. InnerNodeCoordinator.hitTestChild walks children in
       reverse z-order and stops once one is directly hit, unless that node opts into sharing with
       siblings - which a plain pointerInput does not. So a strip declared after the map is the only
       hit target for a press that begins inside it, and the map is never on the dispatch path at
       all. The path is fixed at the press for the life of that pointer.

       The map still draws full-bleed underneath, and the screen's own controls are declared later
       still, so they keep winning hit tests exactly as before. */
    /* The home indicator's own safe-area height rather than a guessed 34dp. Logged once, because
       what iOS actually reports here has not been verified and a zero would silently disable the
       strip. */
    val bottomEdgeInset = with(LocalDensity.current) {
        WindowInsets.safeDrawing.getBottom(this).toDp()
    }.takeIf { it > 0.dp } ?: EDGE_INSET_BOTTOM_FALLBACK
    LaunchedEffect(bottomEdgeInset) { Log.i(EDGE_TAG, "bottom edge strip = $bottomEdgeInset") }

    Box(modifier) {
    MaplibreMap(
        modifier = Modifier.fillMaxSize(),
        baseStyle = BaseStyle.Json(BASE_STYLE),
        zoomRange = 0f..22f,
        cameraState = cameraState,
        // StreetComplete draws its own attribution
        overlay = MapOverlay.None,
        styleState = styleState,
        onMapClick = { position, offset -> onClickMapAt(position, offset); ClickResult.Pass },
        onMapLongClick = onMapLongClick,
    ) {
        val languages = listOf(Locale.current.language)
        val colors = if (MaterialTheme.colors.isLight) MapColors.Light else MapColors.Night

        val overlayIcons = remember(styledElements) { styledElements.overlayIcons() }

        val overlaySource = rememberGeoJsonSource(
            GeoJsonData.Features(FeatureCollection(styledElements.flatMap { it.toGeoJsonFeatures() })),
        )

        MapStyle(
            colors = colors,
            languages = languages,
            belowRoadsContent = {
                // left-and-right lines should be rendered behind the actual road
                if (showOverlay) {
                    StyleableOverlaySideLayer(
                        source = overlaySource,
                        isBridge = false
                    )
                }
            },
            belowRoadsOnBridgeContent = {
                // left-and-right lines should be rendered behind the actual bridge road
                if (showOverlay) {
                    StyleableOverlaySideLayer(
                        source = overlaySource,
                        isBridge = true
                    )
                }
            },
            belowLabelsContent = {
                // labels should be on top of other layers
                DownloadedAreaLayer(downloadedTiles)
                if (showOverlay) {
                    StyleableOverlayLayers(
                        source = overlaySource,
                        onClickElement = { properties ->
                            viewModel.getElementKey(properties)?.let { onClickOverlayElement(it) }
                        }
                    )
                }
                TracksLayers(trackpoints, isRecordingTracks, oldTrackpointsLists)
            },
            aboveLabelsContent = {
                // these are always on top of everything else (including labels)
                if (showOverlay) {
                    StyleableOverlayLabelLayer(
                        source = overlaySource,
                        icons = overlayIcons,
                        color = colors.text,
                        haloColor = colors.textOutline,
                        onClickElement = { properties ->
                            viewModel.getElementKey(properties)?.let { onClickOverlayElement(it) }
                        }
                    )
                }
                shownMarkers?.let { markers ->
                    GeometryMarkersLayers(shownMarkers)
                }
                shownBottomSheet?.geometry?.let { geometry ->
                    FocusedGeometryLayers(geometry)
                }

                if (location != null) {
                    CurrentLocationLayers(location = location, rotation = rotation())
                }

                /* Load every quest icon once, before any of them is needed. Resolving a pin icon
                   the first time costs about a millisecond and finding one already loaded about a
                   tenth of that, so the cost worth removing is the first sight of an icon - which
                   while panning arrives in bursts, as a new kind of quest comes into view. Measured
                   over a fixed pan: the worst single hitch went from 128ms to 41ms, for one 165ms
                   at map open. See research/MAP_PIN_PERF.md.

                   It draws nothing, and it is given a list that never changes, so it composes once
                   and is skipped from then on. */
                /* Every icon a pin can ever have - all quest types and overlays, plus the two
                   icons note edits use - resolved once, with the icon expression built from them
                   once. Panning changes which of them are on screen but never which exist, so
                   there is nothing here for a pan to invalidate. See research/MAP_PIN_PERF.md. */
                val allEditTypes: AllEditTypes = koinInject()
                val allPinIcons = remember(allEditTypes) {
                    (allEditTypes.map { it.icon } +
                        listOf(Res.drawable.quest_create_note, Res.drawable.quest_notes)).distinct()
                }
                val hoistedIconImage = remember { mutableStateOf<Expression<ImageValue>?>(null) }
                PinIconImage(allPinIcons, hoistedIconImage)
                val iconImage = hoistedIconImage.value.takeIf { MapPerf.hoistIconExpression }

                // normal quest pins are not shown while edit history sidebar is open
                if (isShowingUndoHistorySidebar) {
                    PinsLayers(
                        pins = editHistoryPins,
                        onClickPin = { properties ->
                            viewModel.getEditKey(properties)?.let { onClickEdit(it) }
                        },
                        onZoomToCluster = ::zoomToCluster,
                        iconImage = iconImage,
                        prebuiltFeatures = editHistoryFeatures,
                    )
                } else {
                    /* hidden rather than removed: leaving the composition would throw away the
                       clustered source and every pin image, and opening or closing a form would
                       then rebuild and re-cluster the lot and re-decode a hundred-odd icons.
                       Android sets visibility on the layers for the same reason. */
                    PinsLayers(
                        pins = questPins,
                        onClickPin = { properties ->
                            viewModel.getQuestKey(properties)?.let { onClickQuest(it) }
                        },
                        onZoomToCluster = ::zoomToCluster,
                        visible = showQuestPins,
                        iconImage = iconImage,
                        prebuiltFeatures = questFeatures,
                    )
                }

                selectedPin?.let { (icon, locations) ->
                    SelectedPinsLayer(icon, locations)
                }
            }
        )
    }

        /* Declared after the map, so they are hit first. No consume calls are needed - being the
           hit target is the whole mechanism - but consuming costs nothing and keeps the intent
           obvious if this is ever read next to the old approach. */
        SystemGestureEdgeStrip(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomEdgeInset), "bottom"
        )
        SystemGestureEdgeStrip(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EDGE_INSET_LEFT), "left"
        )
    }
}

// need to refer to the local (font) resources platform-independently
internal val BASE_STYLE = """
    {
      "version": 8,
      "name": "Empty",
      "metadata": {},
      "sources": {},
      "glyphs": "${
        Res.getUri("files/glyphs/Roboto Regular/0-255.pbf")
            .replace("Roboto Regular", "{fontstack}")
            .replace("0-255", "{range}")
            // workaround for https://github.com/maplibre/maplibre-native/issues/4498
            .replace("file:///android_asset/", "asset://")
      }",
      "layers": []
    }
    """.trimIndent()

/** A transparent strip that exists only to be the hit target where a system gesture lives, so the
 *  map underneath is never dispatched the touch at all. See the comment at the Box in [MainMap]
 *  for why this is a sibling rather than a guard wrapped around the map. */
@Composable
private fun SystemGestureEdgeStrip(modifier: Modifier, label: String) {
    Box(modifier.pointerInput(label) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.any { it.pressed && !it.previousPressed }) {
                    Log.i(EDGE_TAG, "$label edge strip took a touch the map will not see")
                }
                event.changes.forEach { it.consume() }
            }
        }
    })
}

/** Compose's back gesture starts here; Apple's screen-edge region is undocumented but is about
 *  20pt in practice. */
private val EDGE_INSET_LEFT = 20.dp

/** Only used if the platform reports no bottom safe-area inset. The home indicator is about 34pt
 *  tall on an iPhone that has one. */
private val EDGE_INSET_BOTTOM_FALLBACK = 34.dp

/** So it can be confirmed from a device that a strip is taking the touch, rather than inferred. */
private const val EDGE_TAG = "Gestures"

/** How much of the map the user's finger covers, as on Android (MainMapFragment) */
private val CLICK_AREA_SIZE = 28.dp

/** Every layer that handles clicks itself, so that a click on one of them does not also count as
 *  a click on the map. Kept next to the layers that define them so the two cannot drift apart. */
private val CLICKABLE_LAYERS = PINS_CLICKABLE_LAYERS + STYLEABLE_OVERLAY_CLICKABLE_LAYERS
