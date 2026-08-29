package de.westnordost.streetcomplete.data.download

import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.util.platform.withBackgroundTask
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs the download as a coroutine, keeping the app alive a bit longer if the user leaves it
 *  while a download is running.
 *
 *  [downloader] is provided lazily because it is only needed once a download actually starts. */
class IosDownloadController(private val downloader: () -> Downloader) : DownloadController {

    private val scope = CoroutineScope(SupervisorJob() + CoroutineName(Downloader.TAG))

    private var job: Job? = null

    override fun download(bbox: BoundingBox, isUserInitiated: Boolean) {
        val currentJob = job
        if (currentJob?.isActive == true) {
            // same as ExistingWorkPolicy.KEEP / .REPLACE on Android: a user-initiated download
            // takes precedence over a running one, an automatic one does not
            if (!isUserInitiated) return
            currentJob.cancel()
        }
        job = scope.launch {
            withBackgroundTask(Downloader.TAG) {
                // Downloader logs and notifies its listeners about any error itself
                runCatching { downloader().download(bbox, isUserInitiated) }
            }
        }
    }
}
