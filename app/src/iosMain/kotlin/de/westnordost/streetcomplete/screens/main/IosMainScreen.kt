package de.westnordost.streetcomplete.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import de.westnordost.streetcomplete.ApplicationConstants
import de.westnordost.streetcomplete.data.download.tiles.asBoundingBoxOfEnclosingTiles
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.data.quest.AutoSyncer
import kotlinx.coroutines.flow.first
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.spatialk.units.extensions.meters
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import de.westnordost.streetcomplete.screens.main.map.toBoundingBox
import de.westnordost.streetcomplete.screens.main.map.toGeoJsonBoundingBox
import de.westnordost.streetcomplete.screens.main.map.toLatLon
import de.westnordost.streetcomplete.util.logs.Log
import de.westnordost.streetcomplete.util.math.area
import de.westnordost.streetcomplete.util.math.enclosingBoundingBox
import de.westnordost.streetcomplete.util.math.distanceTo
import de.westnordost.streetcomplete.util.math.enlargedBy
import de.westnordost.streetcomplete.screens.main.edithistory.EditHistoryViewModel
import de.westnordost.streetcomplete.screens.main.map.MainMap
import de.westnordost.streetcomplete.screens.main.map.layers.Marker as MapMarker
import de.westnordost.streetcomplete.ui.common.quest.MapClick
import de.westnordost.streetcomplete.ui.common.quest.Marker as QuestMarker
import de.westnordost.streetcomplete.screens.about.AboutNavHost
import de.westnordost.streetcomplete.screens.main.map.maplibre.CameraPosition as MapCameraPosition
import de.westnordost.streetcomplete.screens.main.map.toPosition
import de.westnordost.streetcomplete.screens.settings.SettingsDestination
import de.westnordost.streetcomplete.screens.settings.SettingsNavHost
import de.westnordost.streetcomplete.screens.user.UserNavHost
import de.westnordost.streetcomplete.ui.theme.Dimensions
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState

/** The real main screen, i.e. the map with all the controls on top of it.
 *
 *  This is the iOS counterpart of what `MainActivity` does on Android. It is deliberately
 *  incomplete: the callbacks that need things that do not work on iOS yet - navigating to the
 *  other screens, recording tracks, the quest-solved animation - do nothing for now. */
@Composable
fun IosMainScreen() {
    val viewModel: MainViewModel = koinViewModel()
    val editHistoryViewModel: EditHistoryViewModel = koinViewModel()
    val mainBottomSheetViewModel: MainBottomSheetViewModel = koinViewModel()

    val prefs: Preferences = koinInject()

    /* uploads edits as they are made and downloads around the user's location, the same way
       MainActivity hooks it into its lifecycle on Android */
    val autoSyncer: AutoSyncer = koinInject()
    val locationProvider: LocationProvider = koinInject()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, autoSyncer) {
        lifecycleOwner.lifecycle.addObserver(autoSyncer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(autoSyncer) }
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // pick up where the user left off, same as the Android map does
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = prefs.mapPosition.toPosition(),
            zoom = prefs.mapZoom,
            bearing = prefs.mapRotation,
            tilt = prefs.mapTilt,
        )
    )

    val shownBottomSheet by mainBottomSheetViewModel.shownBottomSheet.collectAsState()

    /* The forms that let the user aim at something on the map - split way, move node, create node
       in an overlay - draw their crosshair in the middle of the part of the map that the bottom
       sheet does not cover, not in the middle of the screen. On Android, the map camera is given
       that same padding while a form is open, which moves its target under the crosshair. MapLibre
       Compose has no persistent camera padding, so the position under the crosshair is computed
       here instead - without it, those forms aim half a sheet height too far down.

       positionFromScreenLocation wants an offset from the top left of the map, and this is one
       from the top left of the window: the same thing only as long as the map fills the window,
       as it does here. */
    val windowInfo = LocalWindowInfo.current
    val layoutDirection = LocalLayoutDirection.current
    val crosshairOffset = if (shownBottomSheet != null) {
        Dimensions.getOpenQuestFormMapPadding(windowInfo)
            .centerOffsetIn(windowInfo.containerDpSize, layoutDirection)
    } else {
        null
    }

    /* When a quest is opened, Android moves the map so that the element the quest is about is
       inside the part of the map the form does not cover, zooming in on a single node or out to
       fit a whole way, and puts the camera back where it was when the form is closed. See
       MainActivity.showQuestDetailsOnMap and FocusGeometryMapComponent. Without it the element
       regularly ends up hidden behind the form, which is a large part of what made splitting a way
       so awkward here: the way had to be panned back into view by hand first.

       Quests only, including note quests, which Android also focuses. Not the note form, which
       opens at a position the user has just picked anyway. Overlays are a gap rather than a
       decision: Android shifts the map for them too, by giving the camera the form's padding
       (showOverlayElementDetailsOnMap), which moves the element up out from behind the form
       without changing the target. There is no camera to shift here in the same way, so an
       overlay element tapped in the lower half of the screen still ends up behind the form. */
    val questGeometry = when (val sheet = shownBottomSheet) {
        is ShownBottomSheet.OsmQuest -> sheet.geometry
        is ShownBottomSheet.OsmNoteQuest -> sheet.geometry
        else -> null
    }
    var cameraBeforeFocus by remember { mutableStateOf<CameraPosition?>(null) }
    // crosshairOffset, so that a rotation - which changes the free area entirely - re-fits.
    // Re-firing only ever re-fits: cameraBeforeFocus is captured once, by the guard below.
    LaunchedEffect(questGeometry, crosshairOffset) {
        if (questGeometry != null) {
            // not overwritten if one quest is opened directly after another, as on Android
            if (cameraBeforeFocus == null) cameraBeforeFocus = cameraState.position
            cameraState.focusOn(questGeometry, Dimensions.getOpenQuestFormMapPadding(windowInfo))
        } else {
            cameraBeforeFocus?.let {
                // position and zoom only, as endFocusGeometry does - undoing a rotation the user
                // made while the form was open would be surprising
                val restored = cameraState.position.copy(target = it.target, zoom = it.zoom)
                cameraState.animateTo(restored, UNFOCUS_DURATION)
            }
            cameraBeforeFocus = null
        }
    }

    /* The same for the edit selected in the edit history, except that the camera is deliberately
       not put back afterwards - Android calls clearFocus there, not endFocus */
    val selectedEdit by editHistoryViewModel.selectedEdit.collectAsState()
    LaunchedEffect(selectedEdit) {
        val edit = selectedEdit ?: return@LaunchedEffect
        cameraState.focusOn(editHistoryViewModel.getEditGeometry(edit))
    }

    /* MainScreen needs to know where the map is - among other things, it does not show any bottom
       sheet at all while the camera is unknown */
    LaunchedEffect(cameraState.position, cameraState.viewport, crosshairOffset) {
        val position = cameraState.position
        val target = crosshairOffset?.let { cameraState.positionFromScreenLocation(it) }
            ?: position.target
        viewModel.mapCamera.value = MapCameraPosition(
            position = target.toLatLon(),
            rotation = position.bearing,
            tilt = position.tilt,
            zoom = position.zoom,
        )
        viewModel.metersPerDp.value = cameraState.viewport?.metersPerDpAtTarget ?: 0.0
    }

    // ...and remember where they are now
    LaunchedEffect(cameraState.position) {
        val position = cameraState.position
        prefs.mapPosition = position.target.toLatLon()
        prefs.mapZoom = position.zoom
        prefs.mapRotation = position.bearing
        prefs.mapTilt = position.tilt
    }

    val isShowingUndoHistory by editHistoryViewModel.isShowingSidebar.collectAsState()
    var shownMarkers by remember { mutableStateOf<Collection<MapMarker>?>(null) }
    var lastMapClick by remember { mutableStateOf<MapClick?>(null) }
    var lastQuestSolved by remember { mutableStateOf<QuestSolvedEvent?>(null) }
    // the screens that are their own Activity on Android are shown on top of the map here
    var shownScreen by remember { mutableStateOf<FullScreen?>(null) }

    fun zoomBy(diff: Double) {
        scope.launch {
            val position = cameraState.position
            cameraState.animateTo(position.copy(zoom = (position.zoom + diff).coerceIn(0.0, 22.0)))
        }
    }

    Box(Modifier.fillMaxSize()) {
        MainMap(
            onClickOverlayElement = { elementKey ->
                viewModel.selectedOverlay.value?.let { overlay ->
                    mainBottomSheetViewModel.showElementInOverlay(overlay, elementKey)
                }
            },
            onClickQuest = { questKey -> mainBottomSheetViewModel.showQuest(questKey) },
            onClickEdit = { editKey -> editHistoryViewModel.select(editKey) },
            /* exactly what MainActivity.onClickedMapAt does: while a form is open the click is
               something the form may want to know about, and otherwise it dismisses the edit
               history, which is the only way to get rid of it apart from the back gesture */
            onClickMap = { position, clickAreaSizeInMeters ->
                if (shownBottomSheet != null) {
                    lastMapClick = MapClick(position, clickAreaSizeInMeters)
                } else if (isShowingUndoHistory) {
                    editHistoryViewModel.hideSidebar()
                }
            },
            location = null,
            rotation = null,
            shownBottomSheet = shownBottomSheet,
            shownMarkers = shownMarkers,
            isShowingUndoHistorySidebar = isShowingUndoHistory,
            cameraState = cameraState,
            modifier = Modifier.fillMaxSize(),
        )
        MainScreen(
            viewModel = viewModel,
            editHistoryViewModel = editHistoryViewModel,
            mainBottomSheetViewModel = mainBottomSheetViewModel,
            onClickZoomIn = { zoomBy(+1.0) },
            onClickZoomOut = { zoomBy(-1.0) },
            onZoomDrag = { diff -> zoomBy(diff.toDouble()) },
            onClickCompass = {
                scope.launch { cameraState.animateTo(cameraState.position.copy(bearing = 0.0, tilt = 0.0)) }
            },
            onClickLocation = {
                scope.launch {
                    val request = LocationRequest(LocationAccuracy.High, 30.seconds, 100.meters)
                    val fix = locationProvider.updates(request)
                        .first { it is LocationEvent.Fix } as LocationEvent.Fix
                    val (position, _) = fix.location.position
                    cameraState.animateTo(
                        cameraState.position.copy(
                            target = position,
                            zoom = maxOf(cameraState.position.zoom, 17.0),
                        )
                    )
                }
            },
            onClickLocationPointer = { },
            onClickCreate = { mainBottomSheetViewModel.showCreateNote(null) },
            onClickStopTrackRecording = { },
            onDownload = { viewModel.download(cameraState.downloadArea() ?: return@MainScreen) },
            onClickSettings = { shownScreen = FullScreen.Settings },
            onClickQuestSettings = { shownScreen = FullScreen.QuestSettings },
            onClickAbout = { shownScreen = FullScreen.About },
            onClickProfile = { shownScreen = FullScreen.Profile },
            onClickLogin = { shownScreen = FullScreen.Login },
            onSetMapMarkers = { markers -> shownMarkers = markers.map { it.toMapMarker() } },
            onSolvedQuest = { icon, position ->
                // where on screen it was, so that it can fly from there to the star
                cameraState.screenOffsetOf(position, density)?.let {
                    lastQuestSolved = QuestSolvedEvent(icon, it)
                }
            },
            getOffset = { position -> cameraState.screenOffsetOf(position, density) },
            lastMapClick = lastMapClick,
        )

        lastQuestSolved?.let { LastQuestSolvedEffect(it) }

        val goBack = { shownScreen = null }
        when (shownScreen) {
            null -> {}
            // opaque, so that the map does not show through and keeps rendering behind it
            else -> Surface(Modifier.fillMaxSize()) {
                when (shownScreen) {
                    FullScreen.Settings -> SettingsNavHost(onClickBack = goBack)
                    FullScreen.QuestSettings -> SettingsNavHost(
                        onClickBack = goBack,
                        startDestination = SettingsDestination.QuestSelection,
                    )
                    FullScreen.About -> AboutNavHost(onClickBack = goBack)
                    FullScreen.Profile -> UserNavHost(launchAuth = false, onClickBack = goBack)
                    FullScreen.Login -> UserNavHost(launchAuth = true, onClickBack = goBack)
                    null -> {}
                }
            }
        }
    }
}

/** The screens that are a separate Activity on Android */
private enum class FullScreen { Settings, QuestSettings, About, Profile, Login }

/** The area to download for the currently displayed map area, or null if it is not suitable.
 *  Mirrors what MainActivity.getDownloadArea does on Android, minus the toasts. */
private fun CameraState.downloadArea(): BoundingBox? {
    val displayedArea = viewport?.visibleBoundingBox?.toBoundingBox() ?: return null

    val enclosingBBox = displayedArea.asBoundingBoxOfEnclosingTiles(ApplicationConstants.DOWNLOAD_TILE_ZOOM)
    val areaInSqKm = enclosingBBox.area() / 1000000
    if (areaInSqKm > ApplicationConstants.MAX_DOWNLOADABLE_AREA_IN_SQKM) {
        Log.w("IosMainScreen", "Download area too big")
        return null
    }
    // below a certain threshold, it does not make sense to download, so let's enlarge it
    if (areaInSqKm < ApplicationConstants.MIN_DOWNLOADABLE_AREA_IN_SQKM) {
        val radius = sqrt(1000000 * ApplicationConstants.MIN_DOWNLOADABLE_AREA_IN_SQKM / PI)
        return position.target.toLatLon().enclosingBoundingBox(radius)
    }
    return enclosingBBox
}

/* the map layers and the quest forms each declare their own identical Marker type */
private fun QuestMarker.toMapMarker() =
    MapMarker(geometry = geometry, icon = icon, title = title)

private fun CameraState.screenOffsetOf(
    position: LatLon,
    density: androidx.compose.ui.unit.Density,
): Offset? {
    val dpOffset = screenLocationFromPosition(position.toPosition()) ?: return null
    return with(density) { Offset(dpOffset.x.toPx(), dpOffset.y.toPx()) }
}

/** The middle of what is left of a map of size [size] once [this] padding is applied to it, i.e.
 *  where the forms that let the user aim at something draw their crosshair.
 *
 *  Left and right rather than start and end, because that is how Modifier.padding resolves the
 *  PaddingValues the crosshair is actually drawn with, and how MainActivity computes the same
 *  point on Android. It makes a difference in right-to-left layouts, where the padding for the
 *  form is absolute. */
private fun PaddingValues.centerOffsetIn(
    size: DpSize,
    layoutDirection: LayoutDirection,
): DpOffset {
    val left = calculateLeftPadding(layoutDirection)
    val right = calculateRightPadding(layoutDirection)
    val top = calculateTopPadding()
    val bottom = calculateBottomPadding()
    return DpOffset(
        x = left + (size.width - left - right) / 2,
        y = top + (size.height - top - bottom) / 2,
    )
}

/** How much air to leave around a geometry the map is focused on, as a share of its own size.
 *
 *  Android gets its air from fitting the geometry and then zooming out by a further 0.75, which
 *  leaves the geometry occupying 1/2^0.75 = 59% of the free area however big it is. A share of
 *  its size reproduces that; a fixed distance would not, and would fit a long way edge to edge. */
private const val FOCUS_MARGIN_FRACTION = 0.2

/** The least air to leave, for a geometry that is small or - as most quests are - a single point.
 *
 *  A point has no size to take a share of, so this is also what decides how far a node is zoomed
 *  in on. Note this is NOT the equivalent of Android's hard `min(zoom - 0.75, 19.0)` cap: the
 *  resulting zoom varies with latitude and with the size of the free area, from about 19 at 60
 *  degrees on a phone to about 20 at the equator, and more again on a tablet. That is a knowingly
 *  accepted difference - the range is a reasonable one to be in - not an equivalence. */
private const val MIN_FOCUS_MARGIN_IN_METERS = 20.0

private val FOCUS_DURATION = 450.milliseconds
private val UNFOCUS_DURATION = 300.milliseconds

/** Moves the camera so that all of [geometry] is visible in the part of the map left over by
 *  [padding], the way Android's FocusGeometryMapComponent.beginFocusGeometry does.
 *
 *  The padding is what puts the geometry under the crosshair rather than in the middle of the
 *  screen; it is applied while fitting rather than kept on the camera, which is all that is needed
 *  here and all that MapLibre Compose offers. */
private suspend fun CameraState.focusOn(
    geometry: ElementGeometry,
    padding: PaddingValues = PaddingValues(),
) {
    val bounds = geometry.bounds
    val margin = max(MIN_FOCUS_MARGIN_IN_METERS, bounds.min.distanceTo(bounds.max) * FOCUS_MARGIN_FRACTION)
    animateTo(
        boundingBox = bounds.enlargedBy(margin).toGeoJsonBoundingBox(),
        bearing = position.bearing,
        tilt = position.tilt,
        padding = padding,
        duration = FOCUS_DURATION,
    )
}
