package de.westnordost.streetcomplete.screens.main

import androidx.compose.foundation.layout.Box
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
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.screens.main.edithistory.EditHistoryViewModel
import de.westnordost.streetcomplete.screens.main.map.MainMap
import de.westnordost.streetcomplete.screens.main.map.layers.Marker as MapMarker
import de.westnordost.streetcomplete.ui.common.quest.Marker as QuestMarker
import de.westnordost.streetcomplete.screens.main.map.toPosition
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
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

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cameraState = rememberCameraState()

    val shownBottomSheet by mainBottomSheetViewModel.shownBottomSheet.collectAsState()
    var shownMarkers by remember { mutableStateOf<Collection<MapMarker>?>(null) }

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
            onDownload = { },
            onClickSettings = { },
            onClickQuestSettings = { },
            onClickAbout = { },
            onClickProfile = { },
            onClickLogin = { },
            onSetMapMarkers = { markers -> shownMarkers = markers.map { it.toMapMarker() } },
            onSolvedQuest = { _, _ -> },
            getOffset = { position -> cameraState.screenOffsetOf(position, density) },
        )
    }
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
