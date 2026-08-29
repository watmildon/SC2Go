package de.westnordost.streetcomplete.util.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/** Runs [block], asking the system for additional execution time in case the app is sent to the
 *  background while it is still running.
 *
 *  This is the closest equivalent to running work in an expedited `WorkManager` job on Android:
 *  the work is not deferred, it just isn't cut short immediately when the user leaves the app.
 *  Unlike on Android, the work cannot start or continue after the app has been suspended - iOS
 *  grants only a limited amount of extra time (see [UIApplication.backgroundTimeRemaining]) and
 *  calls the expiration handler when it runs out. */
suspend fun <T> withBackgroundTask(name: String, block: suspend () -> T): T {
    val task = withContext(Dispatchers.Main) { BackgroundTask(name) }
    try {
        return block()
    } finally {
        withContext(NonCancellable + Dispatchers.Main) { task.end() }
    }
}

private class BackgroundTask(name: String) {
    private var id: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    init {
        id = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) { end() }
    }

    fun end() {
        val id = id
        if (id != UIBackgroundTaskInvalid) {
            this.id = UIBackgroundTaskInvalid
            UIApplication.sharedApplication.endBackgroundTask(id)
        }
    }
}
