package de.westnordost.streetcomplete

import de.westnordost.streetcomplete.data.Preloader
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
        koin.get<Preloader>().preload()
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
