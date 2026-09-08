package de.westnordost.streetcomplete.data.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android's Battery Saver, from the PowerManager.
 *
 *  Registered as a single: the receiver is registered at construction, on the application
 *  context, and never unregistered - it lives as long as the process. A factory would register
 *  one receiver per instance. */
class AndroidPowerSaveSource(context: Context) : PowerSaveSource {

    private val powerManager = context.getSystemService<PowerManager>()!!

    private val _isSystemLowPower = MutableStateFlow(powerManager.isPowerSaveMode)

    override val isSystemLowPower: StateFlow<Boolean> = _isSystemLowPower.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // re-read rather than trust the intent: it carries no extra for the new state
                _isSystemLowPower.value = powerManager.isPowerSaveMode
            }
        }
        /* NOT_EXPORTED is required from targetSdk 34 on for a context-registered receiver. The
           broadcast is a protected one that only the system sends, which is still delivered. */
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
