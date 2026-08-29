package de.westnordost.streetcomplete

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.data.preferences.Theme
import de.westnordost.streetcomplete.ui.theme.AppTheme
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

fun MainViewController(): UIViewController {
    val viewController = ComposeUIViewController {
        ApplySelectedTheme()
        AppTheme {
            IosApp()
        }
    }
    /* also here and not only in ApplySelectedTheme, because that only runs once there is a
       composition: without it, a dark themed app is drawn light for the first frame */
    viewController.overrideUserInterfaceStyle =
        KoinPlatform.getKoin().get<Preferences>().theme.userInterfaceStyle
    return viewController
}

/** Applies the theme selected in the app by overriding the user interface style, which is what
 *  AppCompatDelegate.setDefaultNightMode does on Android.
 *
 *  Deliberately not done by handing a darkTheme to [AppTheme]: that would only cover what Compose
 *  draws itself. Everything else keeps asking the system - the dark variants of the drawables, the
 *  keyboard, the alerts presented by the map app launcher - so the app would end up dark with
 *  light icons on it. Overriding the style instead makes isSystemInDarkTheme() itself return the
 *  selected theme, so all of that follows along, as it does on Android. */
@Composable
private fun ApplySelectedTheme() {
    val prefs: Preferences = koinInject()
    var theme by remember { mutableStateOf(prefs.theme) }
    DisposableEffect(prefs) {
        // held onto until disposed, as the preferences only keep a weak reference to it
        val listener = prefs.onThemeChanged { theme = it }
        onDispose { listener.deactivate() }
    }
    LaunchedEffect(theme) { setUserInterfaceStyle(theme.userInterfaceStyle) }
}

private val Theme.userInterfaceStyle: UIUserInterfaceStyle get() = when (this) {
    Theme.LIGHT -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
    Theme.DARK -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
    Theme.SYSTEM -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
}

/** on the windows rather than on this view controller, so that it also applies to what is
 *  presented on top of it */
private fun setUserInterfaceStyle(style: UIUserInterfaceStyle) {
    for (scene in UIApplication.sharedApplication.connectedScenes) {
        for (window in (scene as? UIWindowScene)?.windows.orEmpty()) {
            (window as? UIWindow)?.overrideUserInterfaceStyle = style
        }
    }
}
