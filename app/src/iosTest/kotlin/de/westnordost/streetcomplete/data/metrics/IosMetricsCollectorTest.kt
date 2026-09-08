package de.westnordost.streetcomplete.data.metrics

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.NSMeasurement
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUnitDuration
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* MetricKit never delivers a payload on the simulator, so the one thing that cannot be tested here
   is parsing a real MXMetricPayload. What can be tested is everything the payload is fed *through*,
   which is where the quiet mistakes live. */
class IosMetricsCollectorTest {

    /* The failure this guards against is silent and plausible-looking: MetricKit's measurements do
       not promise a unit, and reading the raw value of one that happened to be in milliseconds
       would report CPU-per-foreground-second a thousand times too high. */
    @Test fun millisecondsAreConvertedToSeconds() {
        val ms = NSMeasurement(doubleValue = 1500.0, unit = NSUnitDuration.milliseconds)
        assertEquals(1.5, ms.seconds()!!, absoluteTolerance = 1e-9)
    }

    @Test fun secondsArePassedThroughUnchanged() {
        val s = NSMeasurement(doubleValue = 42.0, unit = NSUnitDuration.seconds)
        assertEquals(42.0, s.seconds()!!, absoluteTolerance = 1e-9)
    }

    @Test fun minutesAreConvertedToSeconds() {
        val m = NSMeasurement(doubleValue = 2.0, unit = NSUnitDuration.minutes)
        assertEquals(120.0, m.seconds()!!, absoluteTolerance = 1e-9)
    }

    /* Every metric in a payload is optional - a payload from a session that never used location has
       no location metrics at all - so the whole summary has to survive nulls rather than throw
       inside the log call and take the process down with it. */
    @Test fun aMissingMeasurementIsNullRatherThanThrowing() {
        assertNull((null as NSMeasurement?).seconds())
    }

    @Test fun missingValuesFormatAsQuestionMark() {
        assertEquals("?", fmt(null))
    }

    @Test fun valuesAreFormattedToOneDecimal() {
        assertEquals("1.5", fmt(1.54))
        assertEquals("0.0", fmt(0.004))
        assertEquals("123.4", fmt(123.45))
    }

    /* The ratio that the whole exercise exists to produce, computed the way summarise() does it. */
    @Test fun cpuPerForegroundSecondIsComputedFromConvertedUnits() {
        val cpu = NSMeasurement(doubleValue = 30_000.0, unit = NSUnitDuration.milliseconds).seconds()!!
        val foreground = NSMeasurement(doubleValue = 2.0, unit = NSUnitDuration.minutes).seconds()!!
        assertEquals(0.25, cpu / foreground, absoluteTolerance = 1e-9)
        assertEquals("0.2", fmt(cpu / foreground))
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test fun payloadsAreWrittenWhereTheyCanBeRetrieved() {
        val content = "{\"probe\":true}"
        val data = (NSString.create(string = content))
            .dataUsingEncoding(NSUTF8StringEncoding)
        assertNotNull(data)

        val path = writeMetricsPayload(data, "test")
        assertNotNull(path, "writeMetricsPayload returned null - Documents was not writable")
        assertTrue(path.contains("/metrickit/"), "not written into the metrickit directory: $path")
        assertTrue(path.endsWith(".json"), "not written as .json: $path")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path), "file is not there: $path")

        // compared as bytes: reading it back as an NSString needs an explicit type argument here
        val readBack = NSData.dataWithContentsOfFile(path)
        assertNotNull(readBack, "could not read back what was just written")
        assertEquals(data.length, readBack.length, "round-tripped payload is a different size")

        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}
