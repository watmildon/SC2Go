package de.westnordost.streetcomplete.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/** One pending or running sync job, independent of the screen that requested it. */
internal class IosSyncJob(private val name: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineName(name))
    private val mutex = Mutex()
    private var job: Job? = null

    fun launch(replace: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                val previous = job
                if (replace) previous?.cancelAndJoin()
                else if (previous?.isCompleted == false) return@withLock

                job = scope.launch {
                    val runningJob = currentCoroutineContext().job
                    val app = UIApplication.sharedApplication
                    var taskId = UIBackgroundTaskInvalid

                    fun endTask() {
                        if (taskId != UIBackgroundTaskInvalid) {
                            app.endBackgroundTask(taskId)
                            taskId = UIBackgroundTaskInvalid
                        }
                    }

                    taskId = app.beginBackgroundTaskWithName(name, expirationHandler = {
                        runningJob.cancel()
                        // The current edit may keep running after cancellation. End the background
                        // task here before iOS's deadline, rather than waiting for finally.
                        // https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:)
                        endTask()
                    })
                    try {
                        /* The work itself off the main thread: upstream's downloaders no longer
                           switch dispatchers themselves (MapDataDownloader.download and
                           NotesDownloader.download only wrap the database write), so whatever
                           launches them decides where the multi-MB OSM response is parsed - and
                           this scope is Main, because the background task API has to be called
                           from there. Default rather than IO: this is CPU-bound parsing, and the
                           database write inside picks IO for itself. */
                        withContext(Dispatchers.Default) { block() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Downloader/Uploader already log the error and notify their listeners.
                    } finally {
                        endTask()
                    }
                }
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}
