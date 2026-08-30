package de.westnordost.streetcomplete.util.locale

import androidx.compose.ui.text.intl.Locale
import de.westnordost.streetcomplete.util.ktx.toNSLocale
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toNSDateComponents
import kotlinx.datetime.toNSTimeZone
import platform.Foundation.NSCalendar
import platform.Foundation.NSDateFormatter

actual class LocalDateTimeFormatter actual constructor(
    locale: Locale?,
    private val timeZone: TimeZone,
    dateStyle: DateTimeFormatStyle,
    timeStyle: DateTimeFormatStyle,
) {
    private val formatter = NSDateFormatter().also {
        if (locale != null) it.locale = locale.toNSLocale()
        it.dateStyle = dateStyle.toNSDateFormatterStyle()
        it.timeStyle = timeStyle.toNSDateFormatterStyle()
        it.timeZone = timeZone.toNSTimeZone()
    }

    actual fun format(dateTime: LocalDateTime): String {
        /* the components are a wall clock reading, so they have to be interpreted in the time
           zone being formatted for. Without this they are read in the device's own zone and the
           result is out by the difference between the two - nine hours, formatting CET on a
           machine in California. */
        val components = dateTime.toNSDateComponents()
        components.timeZone = timeZone.toNSTimeZone()
        val date = NSCalendar.currentCalendar.dateFromComponents(components)
            ?: return ""
        return formatter.stringFromDate(date)
    }
}
