package de.westnordost.streetcomplete

import de.westnordost.streetcomplete.data.Cleaner
import de.westnordost.streetcomplete.data.FeedsUpdater
import de.westnordost.streetcomplete.data.Preloader
import de.westnordost.streetcomplete.data.download.tiles.DownloadedTilesController
import de.westnordost.streetcomplete.data.edithistory.EditHistoryController
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.data.preferences.ResurveyIntervalsUpdater
import de.westnordost.streetcomplete.util.ktx.nowAsEpochMilliseconds
import de.westnordost.streetcomplete.util.logs.DatabaseLogger
import de.westnordost.streetcomplete.util.logs.KermitLogger
import de.westnordost.streetcomplete.util.logs.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the app does once at startup, i.e. the iOS counterpart of
 *  `StreetCompleteApplication.onCreate` on Android.
 *
 *  Kept in the same order as the Android version so the two can be compared. */
fun initApp() {
    /* before starting Koin, unlike on Android: this one needs no dependencies, and iOS has no
       crash reporting to fall back on, so a failure while starting Koin would be silent */
    Log.instances.add(KermitLogger())

    val koin = initKoin()
    Log.instances.add(koin.get<DatabaseLogger>())

    applicationScope.launch {
        // in one coroutine, so that the pruning happens after the preloading, as on Android
        koin.get<Preloader>().preload()
        koin.get<EditHistoryController>().deleteSyncedOlderThan(
            nowAsEpochMilliseconds() - ApplicationConstants.MAX_UNDO_HISTORY_AGE
        )
    }

    koin.get<FeedsUpdater>().updateNow()

    /* Android schedules this through the WorkManager, once a day and not until an hour after
       start. iOS has nothing comparable that is worth the trouble here, so it is done at start
       instead - but at most daily, because cleaning holds the database lock for as long as it
       takes, which would otherwise be while the map is trying to draw. */
    koin.get<Cleaner>().cleanOldAtMostDaily()

    /* also registers its listener on the resurvey intervals setting, which is why this is needed
       at all: nothing else on iOS ever instantiates it. Relies on it being registered as a single */
    koin.get<ResurveyIntervalsUpdater>().update()

    val prefs = koin.get<Preferences>()
    val lastVersion = prefs.lastDataVersion
    if (BuildConfig.VERSION_NAME != lastVersion) {
        prefs.lastDataVersion = BuildConfig.VERSION_NAME
        // on each new version, invalidate the quest cache
        if (lastVersion != null) koin.get<DownloadedTilesController>().invalidateAll()
    }
}

/** Lives as long as the process does: there is no iOS equivalent of Application.onTerminate to
 *  cancel it in, the process is simply killed. */
private val applicationScope = CoroutineScope(
    SupervisorJob() +
    CoroutineName(TAG) +
    /* SupervisorJob only stops a failure reaching siblings, it does not swallow it, and on
       Kotlin/Native an unhandled coroutine exception takes the whole app down */
    CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught exception", e) }
)

private const val TAG = "Application"
