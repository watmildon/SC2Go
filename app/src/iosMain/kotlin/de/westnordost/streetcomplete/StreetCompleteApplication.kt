package de.westnordost.streetcomplete

import de.westnordost.streetcomplete.data.CacheTrimmer
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
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

/** What the app does once at startup, i.e. the iOS counterpart of
 *  `StreetCompleteApplication.onCreate` on Android.
 *
 *  Kept in the same order as the Android version so the two can be compared. */
@OptIn(ExperimentalNativeApi::class)
fun initApp() {
    /* before starting Koin, unlike on Android: this one needs no dependencies, and iOS has no
       crash reporting to fall back on, so a failure while starting Koin would be silent */
    Log.instances.add(KermitLogger())

    /* Not crash reporting - it does not catch native crashes, and the app still terminates. But
       it puts uncaught Kotlin exceptions in the log before it does, so that they can be read back
       from the log screen instead of vanishing. */
    setUnhandledExceptionHook { e -> Log.e(TAG, "Uncaught exception", e) }

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

    // Android does this by overriding onTrimMemory instead, i.e. not as part of onCreate
    observeMemoryPressure(koin.get<CacheTrimmer>())
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

/** The counterpart of Application.onTrimMemory on Android.
 *
 *  iOS does not grade how scarce the memory is: there is only the one warning, and it comes late,
 *  the next step after it being that the app is killed. So it drops the caches rather than only
 *  trimming them, i.e. only the equivalent of Android's most severe levels is implemented.
 *
 *  Nothing is hooked up to the app going to the background: trimming is by far the more expensive
 *  of the two, and doing it there would be in the window in which iOS expects the app to suspend
 *  promptly. (Android does nothing for its lifecycle levels either, only for memory pressure.) */
private fun observeMemoryPressure(cacheTrimmer: CacheTrimmer) {
    val notifications = NSNotificationCenter.defaultCenter
    notifications.addObserverForName(
        name = UIApplicationDidReceiveMemoryWarningNotification,
        `object` = null,
        queue = null, // UIKit posts this on the main thread, so deliver it there directly
    ) { _ ->
        Log.i(TAG, "Memory warning, dropping caches")
        cacheTrimmer.clearCaches()
    }
}
