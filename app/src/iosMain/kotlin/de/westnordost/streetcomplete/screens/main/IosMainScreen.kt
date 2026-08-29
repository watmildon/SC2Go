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
import kotlin.time.Duration.Companion.seconds
import de.westnordost.streetcomplete.screens.main.map.toBoundingBox
import de.westnordost.streetcomplete.screens.main.map.toLatLon
import de.westnordost.streetcomplete.util.logs.Log
import de.westnordost.streetcomplete.util.math.area
import de.westnordost.streetcomplete.util.math.enclosingBoundingBox
import de.westnordost.streetcomplete.screens.main.edithistory.EditHistoryViewModel
import de.westnordost.streetcomplete.screens.main.map.MainMap
import de.westnordost.streetcomplete.screens.main.map.layers.Marker as MapMarker
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
import kotlin.math.sqrt
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.compose.camera.CameraPosition
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
private fun org.maplibre.compose.camera.CameraState.downloadArea(): BoundingBox? {
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

private fun org.maplibre.compose.camera.CameraState.screenOffsetOf(
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
