package de.westnordost.streetcomplete.screens.main

import androidx.compose.foundation.layout.Box
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
import de.westnordost.streetcomplete.ApplicationConstants
import de.westnordost.streetcomplete.data.download.tiles.asBoundingBoxOfEnclosingTiles
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.preferences.Preferences
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
import de.westnordost.streetcomplete.screens.main.map.toPosition
import de.westnordost.streetcomplete.screens.settings.SettingsDestination
import de.westnordost.streetcomplete.screens.settings.SettingsNavHost
import de.westnordost.streetcomplete.screens.user.UserNavHost
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sqrt
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.runtime.LaunchedEffect
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

    // ...and remember where they are now
    LaunchedEffect(cameraState.position) {
        val position = cameraState.position
        prefs.mapPosition = position.target.toLatLon()
        prefs.mapZoom = position.zoom
        prefs.mapRotation = position.bearing
        prefs.mapTilt = position.tilt
    }

    val shownBottomSheet by mainBottomSheetViewModel.shownBottomSheet.collectAsState()
    var shownMarkers by remember { mutableStateOf<Collection<MapMarker>?>(null) }
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
            onClickEdit = { /* undo history sidebar is not wired up yet */ },
            location = null,
            rotation = null,
            shownBottomSheet = shownBottomSheet,
            shownMarkers = shownMarkers,
            isShowingUndoHistorySidebar = false,
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
            onClickLocation = { /* following the user's location is not wired up yet */ },
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
            onSolvedQuest = { _, _ -> },
            getOffset = { position -> cameraState.screenOffsetOf(position, density) },
        )

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
