package de.westnordost.streetcomplete.data.power

import kotlinx.coroutines.flow.StateFlow

/** Whether the *system* is in its low-power mode: Low Power Mode on iOS, Battery Saver on
 *  Android. One of the two inputs of [LowPowerMode].
 *
 *  A [StateFlow], seeded at construction, rather than a callback flow: [LowPowerMode.isActive] is
 *  itself a StateFlow and needs a value before anyone collects it, and the system's answer is
 *  available synchronously on both platforms. */
interface PowerSaveSource {
    val isSystemLowPower: StateFlow<Boolean>
}
