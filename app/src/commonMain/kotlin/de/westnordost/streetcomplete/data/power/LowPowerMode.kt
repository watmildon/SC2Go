package de.westnordost.streetcomplete.data.power

import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.util.logs.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Whether the app should be saving power: the user asked for it in the settings, **or** the
 *  system is in its low-power mode. Following the system is always on; the settings row says so.
 *
 *  Everything that saves power reads [isActive] and reacts to it, not only at start: iOS turns Low
 *  Power Mode off by itself once the battery reaches 80%, so a flip mid-session is the normal
 *  case, not an edge case. What it changes and why is in LOW_POWER_PLAN.md, Part C.
 *
 *  Owns its scope, the way LocationUpdatesSource does: it lives as long as the process, and the
 *  application scope is private to the platform's application class, which a common class cannot
 *  see. The [dispatcher] exists for the tests, which run the combination on an unconfined one so
 *  that a flip is visible in [isActive] synchronously. */
class LowPowerMode(
    prefs: Preferences,
    powerSaveSource: PowerSaveSource,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(
        SupervisorJob() +
        dispatcher +
        CoroutineName(TAG) +
        /* SupervisorJob only stops a failure reaching siblings, it does not swallow it, and on
           Kotlin/Native an unhandled coroutine exception takes the whole app down */
        CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught exception", e) }
    )

    /* The listener lives in this closure, held by the flow's coroutine until awaitClose: Preferences
       keeps only a weak reference to its listeners, so one registered from the constructor and not
       held anywhere would be collected and the preference would stop being reacted to. The current
       value is sent after registering, not before, so a change in between is not lost. */
    private val reducePowerUse: Flow<Boolean> = callbackFlow {
        val listener = prefs.onReducePowerUseChanged { trySend(it) }
        trySend(prefs.reducePowerUse)
        awaitClose { listener.deactivate() }
    }

    val isActive: StateFlow<Boolean> =
        combine(reducePowerUse, powerSaveSource.isSystemLowPower) { preference, system ->
            preference || system
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            prefs.reducePowerUse || powerSaveSource.isSystemLowPower.value
        )

    companion object {
        private const val TAG = "LowPowerMode"
    }
}
