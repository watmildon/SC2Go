package de.westnordost.streetcomplete.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.westnordost.streetcomplete.ApplicationConstants
import de.westnordost.streetcomplete.data.download.tiles.asBoundingBoxOfEnclosingTiles
import de.westnordost.streetcomplete.data.location.Location
import de.westnordost.streetcomplete.data.location.SurveyChecker
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osmtracks.Trackpoint
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.data.quest.AutoSyncer
import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.resources.create_new_note_unprecise
import de.westnordost.streetcomplete.resources.no_gps_no_quests
import de.westnordost.streetcomplete.resources.turn_on_location_request
import de.westnordost.streetcomplete.screens.about.AboutNavHost
import de.westnordost.streetcomplete.screens.main.controls.LocationState
import de.westnordost.streetcomplete.screens.main.edithistory.EditHistoryViewModel
import de.westnordost.streetcomplete.screens.main.map.MainMap
import de.westnordost.streetcomplete.screens.main.map.getTrackBearing
import de.westnordost.streetcomplete.screens.main.map.layers.Marker as MapMarker
import de.westnordost.streetcomplete.screens.main.map.maplibre.CameraPosition as MapCameraPosition
import de.westnordost.streetcomplete.screens.main.map.toBoundingBox
import de.westnordost.streetcomplete.screens.main.map.toGeoJsonBoundingBox
import de.westnordost.streetcomplete.screens.main.map.toLatLon
import de.westnordost.streetcomplete.screens.main.map.toPosition
import de.westnordost.streetcomplete.screens.settings.SettingsDestination
import de.westnordost.streetcomplete.screens.settings.SettingsNavHost
import de.westnordost.streetcomplete.screens.user.UserNavHost
import de.westnordost.streetcomplete.ui.common.ToastPopup
import de.westnordost.streetcomplete.ui.common.dialogs.ConfirmationDialog
import de.westnordost.streetcomplete.ui.common.quest.MapClick
import de.westnordost.streetcomplete.ui.common.quest.Marker as QuestMarker
import de.westnordost.streetcomplete.ui.theme.Dimensions
import de.westnordost.streetcomplete.util.ktx.nowAsEpochMilliseconds
import de.westnordost.streetcomplete.util.ktx.toLocation
import de.westnordost.streetcomplete.util.logs.Log
import de.westnordost.streetcomplete.util.math.area
import de.westnordost.streetcomplete.util.math.distanceTo
import de.westnordost.streetcomplete.util.math.enclosingBoundingBox
import de.westnordost.streetcomplete.util.math.enlargedBy
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.SystemSettingsLauncher
import org.maplibre.compose.util.ClickResult

/** The real main screen, i.e. the map with all the controls on top of it.
 *
 *  This is the iOS counterpart of what `MainActivity` does on Android, and still incomplete:
 *  there is no compass, so the location marker has no direction cone and the map cannot be turned
 *  by pointing the device, and the geometry a form is about is not highlighted on the map. */
@OptIn(FlowPreview::class)
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
    val mapAppLauncher: MapAppLauncher = koinInject()
    val isOpenLocationAvailable = remember { mapAppLauncher.isAvailable() }
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
    val formCrosshairOffset = Dimensions.getOpenQuestFormMapPadding(windowInfo)
        .centerOffsetIn(windowInfo.containerDpSize, layoutDirection)
    val crosshairOffset = if (shownBottomSheet != null) formCrosshairOffset else null

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

    /* ...and remember where they are now. Debounced because the camera is animated - by following
       especially - and this would otherwise write four preferences on every frame. */
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.position }
            .debounce(SAVE_MAP_POSITION_DELAY)
            .collect { position ->
                prefs.mapPosition = position.target.toLatLon()
                prefs.mapZoom = position.zoom
                prefs.mapRotation = position.bearing
                prefs.mapTilt = position.tilt
            }
    }

    val isShowingUndoHistory by editHistoryViewModel.isShowingSidebar.collectAsState()
    var shownMarkers by remember { mutableStateOf<Collection<MapMarker>?>(null) }
    var lastMapClick by remember { mutableStateOf<MapClick?>(null) }
    var showMapContextMenu by remember { mutableStateOf(false) }
    var lastMapLongPress by remember { mutableStateOf<Pair<DpOffset, LatLon>?>(null) }
    var showZoomInToCreateNote by remember { mutableStateOf(false) }

    /* ------------------------------------ location ------------------------------------ */

    val systemSettingsLauncher: SystemSettingsLauncher = koinInject()
    val isFollowingPosition by viewModel.isFollowingPosition.collectAsState()
    val isNavigationMode by viewModel.isNavigationMode.collectAsState()
    var displayedLocation by remember { mutableStateOf<Location?>(null) }
    var confirmTurnOnLocation by remember { mutableStateOf(false) }
    var showNoLocation by remember { mutableStateOf(false) }
    /* Where the user has been since the last break in reception, most recent last, and the
       stretches before that. Drawn on the map so they can see where they have already been, used
       for the direction of travel in navigation mode, and handed to a note when recording. */
    val track = remember { mutableStateListOf<Trackpoint>() }
    val oldTracks = remember { mutableStateListOf<List<Trackpoint>>() }
    val isRecordingTracks by viewModel.isRecordingTracks.collectAsState()

    /** The end of the track, which is all the direction of travel depends on.
     *
     *  getTrackBearing looks for the last point more than 15m back, so when the user is standing
     *  still - at a crossing, or filling in a form - it finds none and walks the whole list. That
     *  is a whole day of fixes, on every fix, once the track is no longer bounded. */
    fun recentTrack(): List<Trackpoint> = track.takeLast(TRACK_BEARING_LOOKBACK)

    /* Keyed on the size because this composable recomposes on every frame the camera moves, and
       these would otherwise rebuild the whole day's path each time. Points are only ever appended,
       and starting a new stretch always changes both sizes. */
    val trackPositions = remember(track.size) { track.map { it.position } }
    val oldTrackPositions = remember(oldTracks.size) {
        oldTracks.map { stretch -> stretch.map { it.position } }
    }

    /** Ends the current stretch of track and starts a new one, as on a break in reception or when
     *  recording starts or stops. */
    fun startNewTrack() {
        if (track.isNotEmpty()) oldTracks.add(track.toList())
        track.clear()
    }

    /* the first fix zooms in if the map is zoomed far out, but only the first, so that the user
       can zoom out again afterwards without it being undone */
    var hasZoomedToLocation by remember { mutableStateOf(false) }

    // as MainMapFragment.restoreMapState does
    LaunchedEffect(Unit) {
        viewModel.isFollowingPosition.value = prefs.mapIsFollowing
        viewModel.isNavigationMode.value = prefs.mapIsNavigationMode
    }

    /** Puts the user's location back in the middle, tilted and turned the way they are going if
     *  navigation mode is on. Mirrors MainMapFragment.centerCurrentPosition. */
    suspend fun centerOnLocation() {
        val position = displayedLocation?.position ?: return
        val current = cameraState.position
        val navigating = viewModel.isNavigationMode.value
        val zoom = if (!hasZoomedToLocation && current.zoom < 17.0) 18.0 else current.zoom
        /* before the animation, not after: a fix arriving inside those 600ms cancels this block
           at the animateTo, and the next one would zoom in all over again */
        hasZoomedToLocation = true
        cameraState.animateTo(
            current.copy(
                target = position.toPosition(),
                bearing = if (navigating) getTrackBearing(recentTrack()) ?: current.bearing else current.bearing,
                tilt = if (navigating) NAVIGATION_MODE_TILT else current.tilt,
                zoom = zoom,
            ),
            CENTER_ON_LOCATION_DURATION,
        )
    }

    fun setIsFollowingPosition(follow: Boolean) {
        viewModel.isFollowingPosition.value = follow
        prefs.mapIsFollowing = follow
        // so that following again zooms in again, as MainMapFragment.isFollowingPosition does
        if (!follow) hasZoomedToLocation = false
        if (follow) scope.launch { centerOnLocation() }
    }

    fun setIsNavigationMode(navigation: Boolean) {
        viewModel.isNavigationMode.value = navigation
        prefs.mapIsNavigationMode = navigation
        if (navigation) {
            scope.launch { centerOnLocation() }
        } else {
            /* deliberately not resetting the rotation as well - the user may well want to keep
               looking that way, and the compass button is there to get back to north (#5886) */
            scope.launch {
                cameraState.animateTo(cameraState.position.copy(tilt = 0.0), LEAVE_NAVIGATION_DURATION)
            }
        }
    }

    /* Panning is how the user stops the map following them - the location button only ever turns
       following on, exactly as on Android (MainActivity.onPanBegin). It is also what
       userHasMovedCamera means, which is what collapses the attribution popup. */
    LaunchedEffect(cameraState.position) {
        if (cameraState.moveReason != CameraMoveReason.GESTURE) return@LaunchedEffect
        viewModel.userHasMovedCamera.value = true
        if (displayedLocation != null && viewModel.isFollowingPosition.value) {
            setIsFollowingPosition(false)
        }
    }

    val surveyChecker: SurveyChecker = koinInject()
    // only while the app is in the foreground, the way Android's observe() is lifecycle scoped
    LaunchedEffect(locationProvider, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        locationProvider.updates(LocationRequest()).collectLatest { event ->
            viewModel.locationState.value = when (event) {
                is LocationEvent.Fix -> LocationState.UPDATING
                is LocationEvent.Unavailable -> when (event.reason) {
                    LocationUnavailableReason.ServicesDisabled -> LocationState.ALLOWED
                    LocationUnavailableReason.TemporarilyUnavailable -> LocationState.SEARCHING
                    LocationUnavailableReason.PermissionDenied -> LocationState.DENIED
                    LocationUnavailableReason.Unsupported,
                    LocationUnavailableReason.Misconfigured,
                    LocationUnavailableReason.UnexpectedFailure -> null
                }
            }
            when (event) {
                is LocationEvent.Fix -> {
                    val location = event.location.toLocation()
                    displayedLocation = location
                    // what decides whether an edit counts as surveyed rather than armchair mapped
                    surveyChecker.addRecentLocation(location)
                    if (location.accuracy <= MIN_TRACK_ACCURACY) {
                        val now = nowAsEpochMilliseconds()
                        /* after a gap - backgrounded, or no reception - the previous points say
                           nothing about which way the user is going now, and taking a bearing
                           across the gap would turn the map to a heading they are not travelling.
                           So start a new stretch, as Android does - but never while recording, or
                           the track attached to the note would be cut down to whatever came
                           after the gap. */
                        val last = track.lastOrNull()
                        if (last != null &&
                            !viewModel.isRecordingTracks.value &&
                            now - last.time > MAX_TIME_BETWEEN_LOCATIONS
                        ) {
                            startNewTrack()
                        }
                        /* elevation 0: the shared Location type has no altitude, so it is dropped in
                           toLocation() before it gets here. Every <ele> in an uploaded trace will
                           be 0.0 until that type carries it. */
                        track.add(Trackpoint(location.position, now, location.accuracy, 0f))
                    }
                    /* read through the flows rather than the captured values: this collector
                       outlives any one composition. Following is suspended while a form or the
                       edit history is open, the same way MainActivity freezes the map, so that it
                       does not fight what those move the camera to. */
                    val isFrozen = mainBottomSheetViewModel.shownBottomSheet.value != null ||
                        editHistoryViewModel.isShowingSidebar.value
                    if (viewModel.isFollowingPosition.value && !isFrozen) centerOnLocation()
                }
                is LocationEvent.Unavailable -> {
                    displayedLocation = null
                    // through the setter, so the map also untilts and the preference follows
                    if (viewModel.isNavigationMode.value) setIsNavigationMode(false)
                    /* not while recording: the stretch would be drawn as an old track, greyed
                       out, while the stop button is still showing and the recording still
                       running. The gap is not split while recording either, for the same reason */
                    if (!viewModel.isRecordingTracks.value) startNewTrack()
                }
            }
        }
        }
    }

    /* so that the move node form can draw its arrow at the node - MainActivity keeps this up to
       date in updateBottomSheetElementPosition */
    /* Both of these are offsets from the top left of the map, where what wants them expects
       offsets from the top left of the window - the same thing only because the map fills the
       window, as noted above. SideEffect rather than LaunchedEffect: they are plain writes, and
       this runs on every frame the camera moves. */
    SideEffect {
        mainBottomSheetViewModel.geometryOffsetInWindow.value =
            shownBottomSheet?.position?.let { cameraState.screenOffsetOf(it, density) }
        viewModel.displayedPosition.value =
            displayedLocation?.let { cameraState.screenOffsetOf(it.position, density) }
    }
    var lastQuestSolved by remember { mutableStateOf<QuestSolvedEvent?>(null) }
    // the screens that are their own Activity on Android are shown on top of the map here
    var shownScreen by remember { mutableStateOf<FullScreen?>(null) }

    /* Notes are created at the crosshair - MainBottomSheet passes the map position to
       CreateNoteForm - so, as MainActivity.composeNote does, move the long pressed position
       there before opening the form */
    fun createNoteAt(position: LatLon) {
        if (cameraState.position.zoom < ApplicationConstants.NOTE_MIN_ZOOM) {
            showZoomInToCreateNote = true
            return
        }
        mainBottomSheetViewModel.showCreateNote(null)
        scope.launch {
            /* stop wherever the camera is first: moveTo works out where to go by asking the map
               where the position currently is on screen, which is meaningless mid-animation, and
               following may well have one running */
            cameraState.animateTo(cameraState.position, Duration.ZERO)
            cameraState.moveTo(position, formCrosshairOffset, windowInfo.containerDpSize)
        }
    }

    fun startTrackRecording() {
        startNewTrack()
        viewModel.isRecordingTracks.value = true
    }

    fun stopTrackRecording() {
        /* Before anything is changed. Stopping opens a note with the track attached - that is the
           whole point of recording one - and with no fix there is nowhere to put the note, so
           there is nothing to do but keep recording. Android stops anyway and loses the track,
           which is worst exactly when it is most likely: no fix is why reception was lost. */
        val position = displayedLocation?.position ?: return
        viewModel.isRecordingTracks.value = false
        val recorded = track.toList()
        startNewTrack()
        mainBottomSheetViewModel.showCreateNote(recorded.takeIf { it.isNotEmpty() })
        scope.launch {
            cameraState.animateTo(cameraState.position, Duration.ZERO)
            cameraState.moveTo(position, formCrosshairOffset, windowInfo.containerDpSize)
        }
    }

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
            location = displayedLocation,
            // no compass yet, so no direction cone on the location dot
            rotation = null,
            shownBottomSheet = shownBottomSheet,
            shownMarkers = shownMarkers,
            isShowingUndoHistorySidebar = isShowingUndoHistory,
            trackpoints = trackPositions,
            oldTrackpointsLists = oldTrackPositions,
            isRecordingTracks = isRecordingTracks,
            onMapLongClick = { position, offset ->
                /* not while a form or the edit history is open, as MainActivity.onLongPress also
                   refuses: creating a note from here replaces whatever is open, throwing away
                   anything typed into it without asking */
                if (shownBottomSheet == null && !isShowingUndoHistory) {
                    lastMapLongPress = offset to position.toLatLon()
                    showMapContextMenu = true
                }
                ClickResult.Consume
            },
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
                /* back to north and flat, and out of navigation mode - wanting north up means not
                   wanting the map turned in the direction of travel any more.

                   One animation for both: animateTo takes a whole CameraPosition, so letting
                   setIsNavigationMode issue its own would supersede this one and leave the map
                   turned. Android can do it in two because its camera updates are partial. */
                viewModel.isNavigationMode.value = false
                prefs.mapIsNavigationMode = false
                scope.launch {
                    cameraState.animateTo(
                        cameraState.position.copy(bearing = 0.0, tilt = 0.0),
                        LEAVE_NAVIGATION_DURATION,
                    )
                }
            },
            /* one button, two things, as on Android: first tap follows the location, the next
               turns the map in the direction of travel, the one after that turns that off again */
            onClickLocation = {
                when (viewModel.locationState.value) {
                    // the app was refused location, which its own settings page can undo
                    LocationState.DENIED -> confirmTurnOnLocation = true
                    /* location services are off system wide. iOS exposes no URL for that screen -
                       IosSystemSettingsLauncher.canOpenLocationServicesSettings is always false -
                       so do what Android does when it cannot open it either, and just say so */
                    LocationState.ALLOWED -> showNoLocation = true
                    else -> {
                        if (!isFollowingPosition) setIsFollowingPosition(true)
                        else setIsNavigationMode(!isNavigationMode)
                    }
                }
            },
            onClickLocationPointer = { setIsFollowingPosition(true) },
            onClickCreate = { mainBottomSheetViewModel.showCreateNote(null) },
            onClickStopTrackRecording = { stopTrackRecording() },
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

        /* Long pressing the map is the only way to create a note or start recording a track,
           as on Android - there is no button for either */
        MapContextMenu(
            expanded = showMapContextMenu,
            onDismissRequest = { showMapContextMenu = false },
            onClickCreateNote = { lastMapLongPress?.let { (_, position) -> createNoteAt(position) } },
            onClickCreateTrack = { startTrackRecording() },
            isCreateTrackAvailable = true,
            onClickOpenLocation = {
                lastMapLongPress?.let { (_, position) ->
                    mapAppLauncher.openAt(position, cameraState.position.zoom)
                }
            },
            isOpenLocationAvailable = isOpenLocationAvailable,
            offset = lastMapLongPress?.first ?: DpOffset.Zero,
        )

        if (confirmTurnOnLocation) {
            ConfirmationDialog(
                onDismissRequest = { confirmTurnOnLocation = false },
                onConfirmed = { systemSettingsLauncher.openApplicationSettings() },
                text = { Text(stringResource(Res.string.turn_on_location_request)) },
            )
        }

        if (showZoomInToCreateNote) {
            ToastPopup(
                onDismissRequest = { showZoomInToCreateNote = false },
                text = stringResource(Res.string.create_new_note_unprecise),
            )
        }

        if (showNoLocation) {
            ToastPopup(
                onDismissRequest = { showNoLocation = false },
                text = stringResource(Res.string.no_gps_no_quests),
            )
        }

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
private val MOVE_DURATION = 300.milliseconds
private val CENTER_ON_LOCATION_DURATION = 600.milliseconds
private val LEAVE_NAVIGATION_DURATION = 300.milliseconds

/** How long the map has to be still before where it is is worth writing down */
private val SAVE_MAP_POSITION_DELAY = 500.milliseconds
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
        /* flat, whatever the map was doing. Android gets this from freezeMap turning navigation
           mode off while a form is open; fitting a bounding box to a tilted camera would also put
           the geometry somewhere other than where it was asked to go. The tilt comes back by
           itself, on the first fix after the form closes and following resumes. */
        tilt = 0.0,
        padding = padding,
        duration = FOCUS_DURATION,
    )
}

/** Moves the camera, without changing the zoom, so that [position] ends up at [offset] on a map
 *  of size [size].
 *
 *  Subtracting in screen space like this is exact whatever the zoom and the bearing, because the
 *  projection is then a similarity and the two cancel. It is NOT exact when the map is tilted,
 *  which it can be - by gesture, or in navigation mode - and the further the offset is from the
 *  middle the more it is out. Android has no such problem because it moves the camera and sets
 *  its padding in one go, and the map does the off-centre part itself; the same would work here
 *  via MaplibreMap's cameraPadding, which would make this function unnecessary. */
private suspend fun CameraState.moveTo(
    position: LatLon,
    offset: DpOffset,
    size: DpSize,
) {
    val current = screenLocationFromPosition(position.toPosition()) ?: return
    // where the camera target has to be for the position to land on the offset
    val target = positionFromScreenLocation(DpOffset(
        x = current.x + size.width / 2 - offset.x,
        y = current.y + size.height / 2 - offset.y,
    )) ?: return
    animateTo(this.position.copy(target = target), MOVE_DURATION)
}



/** How far the map is tilted when it turns in the direction the user is going, as on Android */
private const val NAVIGATION_MODE_TILT = 60.0

/** Fixes less precise than this are not put on the track, as on Android */
private const val MIN_TRACK_ACCURACY = 20f

/** How many fixes back the direction of travel is worked out from */
private const val TRACK_BEARING_LOOKBACK = 200

/** A longer gap than this between fixes starts the track over, as on Android */
private const val MAX_TIME_BETWEEN_LOCATIONS = 60L * 1000
