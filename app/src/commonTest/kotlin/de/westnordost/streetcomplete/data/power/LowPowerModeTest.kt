package de.westnordost.streetcomplete.data.power

import com.russhwolf.settings.SettingsListener
import de.westnordost.streetcomplete.data.preferences.Preferences
import dev.mokkery.answering.calls
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [LowPowerMode.isActive] is preference OR system, and follows both while the app runs.
 *
 *  Run on an unconfined dispatcher so that every flip is visible synchronously: what is under
 *  test is the combination, not the scheduling. */
class LowPowerModeTest {

    private lateinit var prefs: Preferences
    private val system = FakePowerSaveSource()

    /** What the preference currently is, and the listener the mode registered on it */
    private var preference = false
    private var preferenceListener: ((Boolean) -> Unit)? = null
    private var listenerDeactivated = false

    @BeforeTest fun setUp() {
        prefs = mock()
        every { prefs.reducePowerUse } calls { preference }
        every { prefs.onReducePowerUseChanged(any()) } calls { (callback: (Boolean) -> Unit) ->
            preferenceListener = callback
            object : SettingsListener {
                override fun deactivate() { listenerDeactivated = true }
            }
        }
    }

    private fun createMode() = LowPowerMode(prefs, system, Dispatchers.Unconfined)

    private fun setPreference(value: Boolean) {
        preference = value
        preferenceListener!!(value)
    }

    @Test fun offWhenNeither() {
        assertFalse(createMode().isActive.value)
    }

    @Test fun onByPreferenceAlone() {
        preference = true
        assertTrue(createMode().isActive.value)
    }

    @Test fun onBySystemAlone() {
        system.isSystemLowPower.value = true
        assertTrue(createMode().isActive.value)
    }

    @Test fun onByBoth() {
        preference = true
        system.isSystemLowPower.value = true
        assertTrue(createMode().isActive.value)
    }

    /** iOS turns Low Power Mode off by itself at 80% charge: the flip mid-session is the normal case */
    @Test fun followsTheSystemFlippingWhileRunning() {
        val mode = createMode()
        assertFalse(mode.isActive.value)

        system.isSystemLowPower.value = true
        assertTrue(mode.isActive.value)

        system.isSystemLowPower.value = false
        assertFalse(mode.isActive.value)
    }

    @Test fun followsThePreferenceChangingWhileRunning() {
        val mode = createMode()
        assertFalse(mode.isActive.value)

        setPreference(true)
        assertTrue(mode.isActive.value)

        setPreference(false)
        assertFalse(mode.isActive.value)
    }

    /** The user who asked for it keeps it when the system's mode ends, and the other way round */
    @Test fun staysOnWhileEitherIsOn() {
        preference = true
        system.isSystemLowPower.value = true
        val mode = createMode()

        system.isSystemLowPower.value = false
        assertTrue(mode.isActive.value, "the preference alone should keep it on")

        system.isSystemLowPower.value = true
        setPreference(false)
        assertTrue(mode.isActive.value, "the system alone should keep it on")

        system.isSystemLowPower.value = false
        assertFalse(mode.isActive.value)
    }

    /** The preference listener is only held weakly by Preferences: the mode must hold it itself,
     *  for as long as it lives, which is the life of the process */
    @Test fun keepsThePreferenceListenerRegistered() {
        createMode()
        assertNotNull(preferenceListener, "no listener was registered")
        assertFalse(listenerDeactivated)
    }
}

private class FakePowerSaveSource : PowerSaveSource {
    override val isSystemLowPower = MutableStateFlow(false)
}
