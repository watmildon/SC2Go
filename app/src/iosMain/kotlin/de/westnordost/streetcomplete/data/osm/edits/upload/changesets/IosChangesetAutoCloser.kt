package de.westnordost.streetcomplete.data.osm.edits.upload.changesets

import de.westnordost.streetcomplete.data.AuthorizationException
import de.westnordost.streetcomplete.data.ConnectionException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Closes open changesets after the given delay.
 *
 *  Other than the Android implementation, which enqueues a `WorkManager` job, this only closes the
 *  changesets if the app is still running when the delay elapsed: iOS' equivalent for deferrable
 *  background work, `BGTaskScheduler`, only ever runs at a time of the system's choosing, which
 *  may well be hours later or not at all.
 *  This is acceptable because the OSM API closes open changesets by itself after one hour anyway,
 *  closing them earlier is merely nice-to-have.
 *
 *  [openChangesetsManager] is provided lazily to break the dependency cycle between it and this. */
class IosChangesetAutoCloser(
    private val openChangesetsManager: () -> OpenChangesetsManager
) : ChangesetAutoCloser {

    private val scope = CoroutineScope(SupervisorJob() + CoroutineName("AutoCloseChangesets"))

    private var job: Job? = null

    override fun enqueue(delayInMilliseconds: Long) {
        // same as ExistingWorkPolicy.REPLACE on Android: the delay counts from the last activity
        job?.cancel()
        job = scope.launch {
            delay(delayInMilliseconds)
            try {
                openChangesetsManager().closeOldChangesets()
            } catch (e: ConnectionException) {
                // wasn't able to connect to the server (i.e. connection timeout). Oh well, then,
                // never mind. The OSM API closes open changesets after 1 hour anyway.
            } catch (e: AuthorizationException) {
                // the user may not be authorized yet (or not be authorized anymore) #283
                // nothing we can do about here. He will have to re-authenticate when he next opens
                // the app
            }
        }
    }
}
