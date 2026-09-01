package de.westnordost.streetcomplete.util

/** Android applies the selected language as soon as it is picked - `LocaleList.setDefault` plus
 *  `BaseActivity` recreating itself - so there is never a change left waiting. */
actual fun isLanguageChangePending(selectedLanguage: String?): Boolean = false
