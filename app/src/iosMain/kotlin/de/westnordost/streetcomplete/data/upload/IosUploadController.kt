package de.westnordost.streetcomplete.data.upload

import de.westnordost.streetcomplete.util.platform.withBackgroundTask
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs the upload as a coroutine, keeping the app alive a bit longer if the user leaves it while
 *  an upload is running.
 *
 *  [uploader] is provided lazily because it is only needed once an upload actually starts. */
class IosUploadController(private val uploader: () -> Uploader) : UploadController {

    private val scope = CoroutineScope(SupervisorJob() + CoroutineName(Uploader.TAG))

    private var job: Job? = null

    override fun upload(isUserInitiated: Boolean) {
        // same as ExistingWorkPolicy.KEEP on Android: a running upload picks up any new edits
        // anyway, so there is nothing to gain from starting a second one
        if (job?.isActive == true) return
        job = scope.launch {
            withBackgroundTask(Uploader.TAG) {
                // Uploader logs and notifies its listeners about any error itself
                runCatching { uploader().upload() }
            }
        }
    }
}
