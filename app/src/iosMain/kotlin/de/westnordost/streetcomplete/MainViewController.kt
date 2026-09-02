package de.westnordost.streetcomplete

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.ComposeUIViewController
import de.westnordost.streetcomplete.data.preferences.Preferences
import de.westnordost.streetcomplete.data.preferences.Theme
import de.westnordost.streetcomplete.ui.theme.AppTheme
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform
import platform.Foundation.NSLocale
import platform.Foundation.characterDirectionForLanguage
import platform.Foundation.NSLocaleLanguageDirectionRightToLeft
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

fun MainViewController(): UIViewController {
    val viewController = ComposeUIViewController {
        ApplySelectedTheme()
        SwitchLanguageForTesting()
        WithSelectedLanguage {
            AppTheme {
                WithBuildStamp {
                    IosApp()
                }
            }
        }
    }
    /* also here and not only in ApplySelectedTheme, because that only runs once there is a
       composition: without it, a dark themed app is drawn light for the first frame */
    viewController.overrideUserInterfaceStyle =
        KoinPlatform.getKoin().get<Preferences>().theme.userInterfaceStyle
    return viewController
}

/** Rebuilds everything below it whenever the selected language changes, which is what makes the
 *  change show without relaunching the app.
 *
 *  [observeSelectedLanguage] has by then already written the new language to `AppleLanguages`, so
 *  `NSLocale` - and through it Compose's string resources, the number and date formatters and the
 *  feature dictionary - returns it. But all of those are resolved *while composing*, and nothing
 *  invalidates a composition when a user default changes, so what is already on screen keeps the
 *  language it was composed with. Throwing that composition away and building it again is the
 *  counterpart of `ActivityCompat.recreate` in `BaseActivity.onRestart` on Android.
 *
 *  Three things the bare recreate got wrong, all of which Android gets right for free:
 *
 *  `rememberSaveable` state is carried across by hand. The obvious `SaveableStateHolder` does not
 *  work here: it saves a subtree's state when that subtree is *disposed*, which Compose does after
 *  composing the replacement, so the new tree reads an empty store and everything resets. Saving
 *  from the listener instead happens before the key changes, so the values are there to restore
 *  into. This is what `savedInstanceState` does across an activity recreate; anything in a plain
 *  `remember` is still lost, exactly as it is there.
 *
 *  The layout direction is provided rather than inherited. UIKit resolves the process's direction
 *  once at launch, so a right-to-left language picked while running left the layout unmirrored -
 *  Arabic text in a left-to-right layout. On Android the recreated activity picks it up from the
 *  configuration. What UIKit itself draws - the keyboard, system alerts - stays as resolved at
 *  launch and only follows on the next one. */
@Composable
private fun WithSelectedLanguage(content: @Composable () -> Unit) {
    val prefs: Preferences = koinInject()
    var language by remember { mutableStateOf(prefs.language) }
    var registry by remember {
        mutableStateOf(SaveableStateRegistry(restoredValues = null, canBeSaved = { true }))
    }
    /* Deactivating and reactivating rather than key(language): key() mixes the language into
       currentCompositeKeyHash, which is what rememberSaveable derives its keys from, so restored
       values would be filed under keys the new composition never asks for. Deactivating leaves the
       call sites where they are, so the keys still match. */
    var isActive by remember { mutableStateOf(true) }
    DisposableEffect(prefs) {
        // held onto until disposed, as the preferences only keep a weak reference to it
        val listener = prefs.onLanguageChanged { newLanguage ->
            /* order matters: this runs before the recomposition the change triggers, so the old
               composition is still there to be saved out of */
            registry = SaveableStateRegistry(registry.performSave(), canBeSaved = { true })
            language = newLanguage
            isActive = false
        }
        onDispose { listener.deactivate() }
    }
    LaunchedEffect(isActive) { if (!isActive) isActive = true }
    CompositionLocalProvider(
        LocalSaveableStateRegistry provides registry,
        LocalLayoutDirection provides layoutDirectionOf(language),
    ) {
        ReusableContentHost(isActive) { content() }
    }
}

/** [language] is a tag like `pt-BR`, while `characterDirectionForLanguage` wants a bare language
 *  code. `null` means the system default, which is what `preferredLanguages` reports once the
 *  override has been removed. */
private fun layoutDirectionOf(language: String?): LayoutDirection {
    val tag = language ?: NSLocale.preferredLanguages.firstOrNull() as? String
    val code = tag?.substringBefore('-')?.substringBefore('_') ?: return LayoutDirection.Ltr
    val isRtl = NSLocale.characterDirectionForLanguage(code) == NSLocaleLanguageDirectionRightToLeft
    return if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
}

/** Changes the language while the app is running, so the live switch can be exercised without
 *  driving the settings UI:
 *
 *      xcrun simctl launch booted <bundle id> -setlanguage de -setlanguagedelay 4
 *
 *  `-setlanguage system` goes back to the system default. Deliberately outside
 *  [WithSelectedLanguage] so that recreating the composition does not run it a second time. */
@Composable
private fun SwitchLanguageForTesting() {
    if (!BuildConfig.DEBUG) return
    val prefs: Preferences = koinInject()
    LaunchedEffect(Unit) {
        val defaults = NSUserDefaults.standardUserDefaults
        val language = defaults.stringForKey("setlanguage") ?: return@LaunchedEffect
        val seconds = defaults.doubleForKey("setlanguagedelay").takeIf { it > 0.0 } ?: 4.0
        delay((seconds * 1000).toLong())
        prefs.language = language.takeIf { it != "system" }
    }
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
