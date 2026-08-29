package de.westnordost.streetcomplete

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.data.preferences.Theme
import de.westnordost.streetcomplete.ui.theme.AppTheme
import org.koin.compose.koinInject
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    AppTheme(darkTheme = isDarkTheme()) {
        IosApp()
    }
}

/** Whether to display the app in dark mode, according to the user's setting.
 *
 *  On Android, the setting is applied globally with AppCompatDelegate.setDefaultNightMode, which
 *  makes isSystemInDarkTheme() return it. There is no equivalent on iOS, so it must be resolved
 *  here and handed to the theme. */
@Composable
private fun isDarkTheme(): Boolean {
    val prefs: Preferences = koinInject()
    var theme by remember { mutableStateOf(prefs.theme) }
    DisposableEffect(prefs) {
        // held onto until disposed, as the preferences only keep a weak reference to it
        val listener = prefs.onThemeChanged { theme = it }
        onDispose { listener.deactivate() }
    }
    return when (theme) {
        Theme.LIGHT -> false
        Theme.DARK -> true
        Theme.SYSTEM -> isSystemInDarkTheme()
    }
}
