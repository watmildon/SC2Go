package de.westnordost.streetcomplete.util.locale

import androidx.compose.ui.text.intl.Locale
import de.westnordost.streetcomplete.util.ktx.toNSLocale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toNSDateComponents
import kotlinx.datetime.toNSTimeZone
import platform.Foundation.NSCalendar
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle

actual class LocalTimeFormatter actual constructor(
    locale: Locale?,
    private val timeZone: TimeZone,
    style: DateTimeFormatStyle,
) {
    private val formatter = NSDateFormatter().also {
        if (locale != null) it.locale = locale.toNSLocale()
        it.dateStyle = NSDateFormatterNoStyle
        it.timeStyle = style.toNSDateFormatterStyle()
        it.timeZone = timeZone.toNSTimeZone()
    }

    actual fun format(time: LocalTime): String {
        val dateTime = LocalDateTime(LocalDate(2000, 1, 1), time)
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
