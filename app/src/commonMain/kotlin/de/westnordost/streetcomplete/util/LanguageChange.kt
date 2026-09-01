package de.westnordost.streetcomplete.util

/** Whether [selectedLanguage] has been selected but is not what the app is currently showing,
 *  because the platform can only apply a language change when the app starts.
 *
 *  Always `false` where the change applies immediately, as it does on Android. */
expect fun isLanguageChangePending(selectedLanguage: String?): Boolean
