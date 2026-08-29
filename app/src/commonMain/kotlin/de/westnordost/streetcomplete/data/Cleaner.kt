package de.westnordost.streetcomplete.data

import de.westnordost.streetcomplete.ApplicationConstants
import de.westnordost.streetcomplete.data.download.tiles.DownloadedTilesController
import de.westnordost.streetcomplete.data.logs.LogsController
import de.westnordost.streetcomplete.data.maptiles.MapTilesDownloader
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataController
import de.westnordost.streetcomplete.data.osmcal.CalendarEventsController
import de.westnordost.streetcomplete.data.osmnotes.NoteController
import de.westnordost.streetcomplete.data.quest.QuestTypeRegistry
import de.westnordost.streetcomplete.util.ktx.format
import de.westnordost.streetcomplete.util.ktx.nowAsEpochMilliseconds
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.util.ktx.now
import kotlinx.datetime.LocalDate
import de.westnordost.streetcomplete.util.logs.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Deletes old unused persisted data in the background */
class Cleaner(
    private val noteController: NoteController,
    private val mapDataController: MapDataController,
    private val questTypeRegistry: QuestTypeRegistry,
    private val downloadedTilesController: DownloadedTilesController,
    private val logsController: LogsController,
    private val mapTilesDownloader: MapTilesDownloader,
    private val calendarEventsController: CalendarEventsController,
    private val prefs: Preferences,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineName(TAG) + Dispatchers.IO +
        /* cleaning up is entirely optional, so it must never be able to take the app down, which
           an unhandled failure would do on Kotlin/Native - and it runs at startup on iOS, so that
           would be a crash loop with no way back into the app */
        CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unable to clean up", e) }
    )

    /** clean up at most daily: this holds the database lock for as long as it takes, so it should
     *  not be done on every app start */
    fun cleanOldAtMostDaily() {
        val today = LocalDate.now()
        val lastCleanup = prefs.lastCleanup
        if (lastCleanup != null && lastCleanup >= today) return

        cleanOld()
    }

    fun cleanOld() = scope.launch {
        prefs.lastCleanup = LocalDate.now()
        val time = nowAsEpochMilliseconds()

        val oldDataTimestamp = nowAsEpochMilliseconds() - ApplicationConstants.DELETE_OLD_DATA_AFTER
        while (true) {
            val deleted = noteController.deleteOlderThan(oldDataTimestamp, MAX_DELETE_ELEMENTS)
            if (deleted < MAX_DELETE_ELEMENTS) break
        }
        while (true) {
            val deleted = mapDataController.deleteOlderThan(oldDataTimestamp, MAX_DELETE_ELEMENTS)
            if (deleted < MAX_DELETE_ELEMENTS) break
        }
        downloadedTilesController.deleteOlderThan(oldDataTimestamp)
        // do this after cleaning map data and notes, because some metadata rely on map data
        questTypeRegistry.forEach { it.deleteMetadataOlderThan(oldDataTimestamp) }

        val oldLogTimestamp = nowAsEpochMilliseconds() - ApplicationConstants.DELETE_OLD_LOG_AFTER
        logsController.deleteOlderThan(oldLogTimestamp)

        calendarEventsController.deleteOld()

        Log.i(TAG, "Cleaning took ${((nowAsEpochMilliseconds() - time) / 1000.0).format(1)}s")
    }

    fun cleanAll() = scope.launch {
        mapTilesDownloader.clear()
        downloadedTilesController.clear()
        mapDataController.clear()
        noteController.clear()
        logsController.clear()
        questTypeRegistry.forEach { it.deleteMetadataOlderThan(nowAsEpochMilliseconds()) }
    }

    companion object {
        private const val TAG = "Cleaner"

        /* Why deleting at most that many elements? Because I got crash reports of an out of memory
         * error in NodeDao.deleteAll: Some people managed to download so many OSM elements (in one
         * session) that Android is out of memory just joining all the ids that should be
         * deleted because they are too old to a string. 😐 */
        private const val MAX_DELETE_ELEMENTS = 100_000
    }
}
