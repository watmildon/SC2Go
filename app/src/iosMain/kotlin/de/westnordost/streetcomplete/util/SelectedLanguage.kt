package de.westnordost.streetcomplete.util

import com.russhwolf.settings.SettingsListener
import de.westnordost.streetcomplete.data.preferences.Preferences
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

/** iOS decides which language the app runs in before any of our code runs, by reading the
 *  `AppleLanguages` user default. Writing that default into the app's *own* domain overrides the
 *  system-wide list for this app alone, which is the closest thing iOS has to what
 *  `LocaleList.setDefault(getSelectedLocales(prefs))` does on Android.
 *
 *  It reaches as far as the Android call does: Compose Multiplatform resolves string resources
 *  through NSLocale, and so do NumberFormatter/LocalDateFormatter and the feature dictionary, so
 *  one write moves all of them.
 *
 *  Written at startup it applies to that same launch - NSLocale is read live, and the first
 *  composition happens well after `initApp`. Changed while the app is running it does not: what is
 *  already composed keeps the language it resolved with, and nothing invalidates it. So a change
 *  made in the settings shows fully only on the next launch - see [isLanguageChangePending].
 *
 *  The alternative would have been Compose Multiplatform's own `LocalComposeEnvironment`, which
 *  would apply without a relaunch, but it is still internal as of 1.12.0:
 *
 *      Cannot access 'val LocalComposeEnvironment': it is internal in file.
 *
 *  and it would only have covered the string resources, not the formatters or the dictionary. */
private const val APPLE_LANGUAGES = "AppleLanguages"

/** The selection as it was when iOS resolved the app's language, i.e. the language the app is
 *  actually running in. Read before anything writes to [APPLE_LANGUAGES] this session. */
private var languageAtStart: String? = null
private var isObserving = false

/** The languages the user would get if nothing were selected in the app. Read once at start, both
 *  because it cannot change while the app runs and to keep the work out of the change listener. */
private var systemLanguages: List<String> = emptyList()

/** Guards against re-entering through our own write: [Preferences] is backed by NSUserDefaults, so
 *  storing [APPLE_LANGUAGES] synchronously notifies the very listener that asked for it. Left
 *  unguarded that recursed until the stack ran out. */
private var isApplying = false

/** Applies the selected language now and on every later change, and returns the listener that
 *  keeps doing so. The caller has to hold on to it: preferences only keep a weak reference. */
fun observeSelectedLanguage(prefs: Preferences): SettingsListener {
    languageAtStart = prefs.language
    systemLanguages = readSystemLanguages(languageAtStart)
    isObserving = true
    applySelectedLanguage(prefs.language)
    return prefs.onLanguageChanged { applySelectedLanguage(it) }
}

/** Whether the selected language has changed since the app launched, i.e. the app is still showing
 *  the previous one and has to be restarted before the change is visible. */
actual fun isLanguageChangePending(selectedLanguage: String?): Boolean =
    isObserving && selectedLanguage != languageAtStart

private fun applySelectedLanguage(language: String?) {
    if (isApplying) return
    isApplying = true
    try {
        val defaults = NSUserDefaults.standardUserDefaults
        if (language == null) {
            defaults.removeObjectForKey(APPLE_LANGUAGES)
        } else {
            // as on Android, the selection goes in front of the system languages rather than
            // replacing them, so anything untranslated falls back to what the user would get anyway
            defaults.setObject(listOf(language) + systemLanguages, APPLE_LANGUAGES)
        }
    } finally {
        isApplying = false
    }
}

/** `NSLocale.preferredLanguages` already has any [APPLE_LANGUAGES] written by a previous session
 *  in front of it, so [selected] is taken back out rather than being folded into the fallback list
 *  and accumulating there. Reading the global domain instead would be more direct, but inside the
 *  app sandbox it comes back without the languages. */
private fun readSystemLanguages(selected: String?): List<String> {
    val preferred = NSLocale.preferredLanguages.filterIsInstance<String>()
    if (selected == null) return preferred
    // iOS canonicalises the entries against the device region, so "de" comes back as e.g. "de-US"
    return preferred.filterNot { it == selected || it.startsWith("$selected-") }
}
