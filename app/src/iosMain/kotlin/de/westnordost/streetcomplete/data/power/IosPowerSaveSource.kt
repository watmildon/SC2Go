package de.westnordost.streetcomplete.data.power

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoPowerStateDidChangeNotification
import platform.Foundation.lowPowerModeEnabled  // a category member, so an extension: needs the import

/** iOS' Low Power Mode, from NSProcessInfo.
 *
 *  Registered as a single: the observer is added at construction and never removed, the same as
 *  the memory-warning observer in StreetCompleteApplication - there is nothing in the process'
 *  life to remove it in. A factory would add one observer per instance.
 *
 *  The notification is posted on an arbitrary thread and carries nothing useful - the observer
 *  re-reads the flag rather than trusting the notification. Setting a MutableStateFlow's value
 *  is thread-safe. */
class IosPowerSaveSource : PowerSaveSource {

    private val _isSystemLowPower = MutableStateFlow(NSProcessInfo.processInfo.lowPowerModeEnabled)

    override val isSystemLowPower: StateFlow<Boolean> = _isSystemLowPower.asStateFlow()

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSProcessInfoPowerStateDidChangeNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            _isSystemLowPower.value = NSProcessInfo.processInfo.lowPowerModeEnabled
        }
    }
}
