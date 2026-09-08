package de.westnordost.streetcomplete.data.metrics

import de.westnordost.streetcomplete.data.power.LowPowerMode
import de.westnordost.streetcomplete.screens.main.map.MapPerf
import de.westnordost.streetcomplete.util.ktx.format
import de.westnordost.streetcomplete.util.ktx.nowAsEpochMilliseconds
import de.westnordost.streetcomplete.util.logs.Log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSMeasurement
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTimer
import platform.Foundation.NSUnitDuration
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.MetricKit.MXAnimationMetric
import platform.MetricKit.MXAppRunTimeMetric
import platform.MetricKit.MXCPUMetric
import platform.MetricKit.MXDiagnosticPayload
import platform.MetricKit.MXGPUMetric
import platform.MetricKit.MXLocationActivityMetric
import platform.MetricKit.MXMetricManager
import platform.MetricKit.MXMetricManagerSubscriberProtocol
import platform.MetricKit.MXMetricPayload
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.darwin.NSObject
import platform.posix.CLOCK_PROCESS_CPUTIME_ID
import platform.posix.clock_gettime
import platform.posix.timespec
import kotlin.time.TimeSource

/** Collects MetricKit's daily energy-relevant metrics and puts the interesting ones in the app's
 *  own log, where they can be read back from *About > Show logs* on the device itself.
 *
 *  This exists because the battery question cannot be answered from the source. MetricKit measures
 *  what actually happened on a real survey, and two of its numbers answer the two open questions
 *  directly:
 *
 *  - [MXLocationActivityMetric] reports how long the app spent at *each* GPS accuracy tier. That
 *    settles whether we really sit pinned at `kCLLocationAccuracyBest` for a whole survey, which is
 *    suspect 2 in ENERGY_USE.md and the thing a low-power mode would target first.
 *  - [MXCPUMetric.cumulativeCPUTime] over [MXAppRunTimeMetric.cumulativeForegroundTime] is CPU
 *    seconds burned per second of use. That is the single number a low-power mode has to move, and
 *    the one to compare between two builds.
 *
 *  **Payloads arrive at most once a day**, delivered by iOS when it feels like it - usually shortly
 *  after midnight, covering the previous 24 hours. So this also logs [MXMetricManager.pastPayloads]
 *  at startup, which is up to seven days already sitting on the device: after a survey, relaunching
 *  the next day is enough to see it.
 *
 *  Diagnostics are collected too. [MXDiagnosticPayload] carries hang and crash reports from real
 *  use, which is the only way this project currently learns about a hang that did not happen in
 *  front of a debugger.
 *
 *  MetricKit's ratio is a day's aggregate, and a day may hold a survey in each mode. So the same
 *  ratio is also logged per *foreground stint* - from the app becoming active to it going to the
 *  background - from the process' own CPU clock, with the arm it ran under on the same line. That
 *  is what makes a number attributable to a mode afterwards, see [ForegroundStintLogger].
 *
 *  Nothing here is sent anywhere: the summary goes to the log database on the device, and the full
 *  JSON is written next to it for anyone who wants every field. */
class IosMetricsCollector : NSObject(), MXMetricManagerSubscriberProtocol {

    /** @param lowPowerMode read when a stint ends, for the label on its log line */
    fun start(lowPowerMode: LowPowerMode) {
        ForegroundStintLogger(lowPowerMode).start()
        MXMetricManager.sharedManager.addSubscriber(this)
        /* Up to seven days of payloads that iOS already delivered before this launch. Logged every
           launch, so the same day's numbers will repeat in the log - that is deliberate, since the
           alternative is missing them entirely when a payload arrives while the app is not
           running, which is the normal case. */
        val past = MXMetricManager.sharedManager.pastPayloads
        if (past.isEmpty()) {
            Log.i(TAG, "No past MetricKit payloads yet - iOS delivers the first about a day after install")
        } else {
            Log.i(TAG, "${past.size} past MetricKit payload(s) on this device")
            past.forEach { summarise(it as? MXMetricPayload ?: return@forEach, "past") }
        }
    }

    override fun didReceiveMetricPayloads(payloads: List<*>) {
        Log.i(TAG, "Received ${payloads.size} new MetricKit payload(s)")
        payloads.forEach { summarise(it as? MXMetricPayload ?: return@forEach, "new") }
    }

    override fun didReceiveDiagnosticPayloads(payloads: List<*>) {
        Log.i(TAG, "Received ${payloads.size} MetricKit diagnostic payload(s)")
        payloads.forEach { payload ->
            val diagnostic = payload as? MXDiagnosticPayload ?: return@forEach
            val hangs = diagnostic.hangDiagnostics?.size ?: 0
            val crashes = diagnostic.crashDiagnostics?.size ?: 0
            val cpuExceptions = diagnostic.cpuExceptionDiagnostics?.size ?: 0
            val diskWrites = diagnostic.diskWriteExceptionDiagnostics?.size ?: 0
            Log.w(TAG, "diagnostics: $hangs hang(s), $crashes crash(es), " +
                "$cpuExceptions CPU exception(s), $diskWrites disk-write exception(s)")
            write(diagnostic.JSONRepresentation(), "diagnostic")
        }
    }

    private fun summarise(payload: MXMetricPayload, kind: String) {
        val from = payload.timeStampBegin
        val to = payload.timeStampEnd
        Log.i(TAG, "=== $kind payload: $from .. $to (v${payload.latestApplicationVersion}) ===")

        val foreground = payload.applicationTimeMetrics?.cumulativeForegroundTime.seconds()
        val background = payload.applicationTimeMetrics?.cumulativeBackgroundTime.seconds()
        val cpu = payload.cpuMetrics?.cumulativeCPUTime.seconds()
        val gpu = payload.gpuMetrics?.cumulativeGPUTime.seconds()

        /* The headline. CPU seconds per foreground second is what a low-power mode has to move, and
           what to compare between two builds or two settings of the same build. */
        if (foreground != null && foreground > 0.0 && cpu != null) {
            Log.i(TAG, "CPU ${fmt(cpu)}s over ${fmt(foreground)}s foreground " +
                "= ${fmt(cpu / foreground)} CPU-s per foreground-s")
        } else {
            Log.i(TAG, "runtime: foreground=${fmt(foreground)}s cpu=${fmt(cpu)}s (ratio unavailable)")
        }
        Log.i(TAG, "background ${fmt(background)}s, gpu ${fmt(gpu)}s")

        /* Where the GPS actually sat. "best" here is kCLLocationAccuracyBest, which is what
           LocationRequest()'s default accuracy maps to on iOS - so a large number next to a small
           foreground time is the suspicion in ENERGY_USE.md confirmed. */
        val location = payload.locationActivityMetrics
        if (location == null) {
            Log.i(TAG, "location: no activity in this payload")
        } else {
            Log.i(TAG, "location by accuracy tier (seconds): " +
                "bestForNavigation=${fmt(location.cumulativeBestAccuracyForNavigationTime.seconds())} " +
                "best=${fmt(location.cumulativeBestAccuracyTime.seconds())} " +
                "nearest10m=${fmt(location.cumulativeNearestTenMetersAccuracyTime.seconds())} " +
                "hundred=${fmt(location.cumulativeHundredMetersAccuracyTime.seconds())} " +
                "km=${fmt(location.cumulativeKilometerAccuracyTime.seconds())} " +
                "threeKm=${fmt(location.cumulativeThreeKilometersAccuracyTime.seconds())}")
        }

        /* Hitch time ratio is Apple's own measure of janky frames - directly comparable to what the
           MapPerf harness measures on the simulator, but from a real device and a real survey. */
        payload.animationMetrics?.scrollHitchTimeRatio?.let {
            Log.i(TAG, "scroll hitch time ratio: ${it.doubleValue}")
        }
        payload.displayMetrics?.averagePixelLuminance?.let {
            Log.i(TAG, "average pixel luminance: ${it.averageMeasurement.doubleValue}")
        }

        write(payload.JSONRepresentation(), "metric")
    }

    private fun write(json: NSData, kind: String) {
        val path = writeMetricsPayload(json, kind)
        if (path != null) Log.i(TAG, "wrote full payload to $path")
        else Log.w(TAG, "could not write payload")
    }
}

/** Writes a payload next to the log database and returns where it went, or null if it could not.
 *
 *  Top level and internal so that the directory creation and the write can be exercised by a test
 *  without a real MetricKit payload, which is the one thing that cannot be produced on demand.
 *
 *  Retrieve the files with:
 *  `xcrun devicectl device copy from --device <id> --domain-type appDataContainer
 *  --domain-identifier com.watmildon.sc2go --source Documents/metrickit --destination .` */
@OptIn(ExperimentalForeignApi::class)
internal fun writeMetricsPayload(json: NSData, kind: String): String? {
    val dir = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true
    ).firstOrNull() as? String ?: return null
    val target = "$dir/metrickit"
    NSFileManager.defaultManager.createDirectoryAtPath(target, true, null, null)
    val path = "$target/$kind-${nowAsEpochMilliseconds()}.json"
    return if (json.writeToFile(path, true)) path else null
}

/** Logs the process' CPU seconds per wall second for each foreground stint, with the arm.
 *
 *  Starts a stint on UIApplicationDidBecomeActive and ends it on UIApplicationDidEnterBackground.
 *  Becoming active without a background in between - after an interruption such as a call, or
 *  Control Center - restarts the stint rather than ending one, so those stints are undercounted a
 *  little rather than double counted. The observers are held for the life of the process, the way
 *  the memory-warning observer in StreetCompleteApplication is; UIKit posts both on the main
 *  thread, so there is nothing to synchronise.
 *
 *  Locking the screen posts DidEnterBackground, so a stint that ends that way gets its line. A
 *  stint that ends in the process being killed - which is how every `power_arm.sh` run ends,
 *  and how iOS ends a suspended app - never posts it, so the same line is also
 *  logged every [STINT_LOG_INTERVAL_SECONDS] while in the foreground, marked "so far" and always
 *  counted from the start of the stint. The last one before the kill then carries the number. The
 *  timer is on the common run-loop modes so that it keeps firing while the map is being dragged,
 *  which is exactly when the number is wanted. The cost is one log-database insert per interval.
 *
 *  Never throws: a clock that could not be read logs a stint without a ratio. It is only a log
 *  line and must not be able to take the app down on its way to the background. */
private class ForegroundStintLogger(private val lowPowerMode: LowPowerMode) {

    private class Stint(
        val cpuSeconds: Double?,
        val wall: TimeSource.Monotonic.ValueTimeMark,
        val timer: NSTimer,
    )

    private var stint: Stint? = null

    fun start() {
        val notifications = NSNotificationCenter.defaultCenter
        notifications.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            // an interruption restarts the stint; its timer must not keep logging the old one
            stint?.timer?.invalidate()
            val timer = NSTimer.timerWithTimeInterval(STINT_LOG_INTERVAL_SECONDS, repeats = true) { _ ->
                stint?.let { log(it, soFar = true) }
            }
            NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
            stint = Stint(processCpuSeconds(), TimeSource.Monotonic.markNow(), timer)
        }
        notifications.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            val started = stint ?: return@addObserverForName
            stint = null
            started.timer.invalidate()
            log(started, soFar = false)
        }
    }

    private fun log(started: Stint, soFar: Boolean) {
        try {
            val wall = started.wall.elapsedNow().inWholeMilliseconds / 1000.0
            val cpu = processCpuSeconds()?.let { end -> started.cpuSeconds?.let { end - it } }
            val ratio = if (cpu != null && wall > 0.0) cpu / wall else null
            /* the arm, on the same line: without it the number cannot be attributed to a mode or
               a flag setting later, when the log is read back */
            // three decimals: the arms are expected to differ in the second one
            val what = if (soFar) "foreground stint so far" else "foreground stint"
            Log.i(TAG, "$what: CPU ${fmt(cpu)}s over ${fmt(wall)}s wall " +
                "= ${ratio?.format(3) ?: "?"} CPU-s per wall-s " +
                "maxfps=${MapPerf.maxFps} gpsacc=${MapPerf.gpsAccuracy} " +
                "gpsdist=${MapPerf.gpsDistanceM} compassms=${MapPerf.compassIntervalMs} " +
                "lowpower=${lowPowerMode.isActive.value}")
        } catch (e: Exception) {
            Log.e(TAG, "could not log the foreground stint", e)
        }
    }
}

/** The CPU time this process has used so far, in seconds, or null if the clock could not be
 *  read. Both threads' and the kernel's time on the process' behalf, which is what MetricKit's
 *  cumulativeCPUTime counts too. */
@OptIn(ExperimentalForeignApi::class)
internal fun processCpuSeconds(): Double? = memScoped {
    val time = alloc<timespec>()
    // clockid_t is a UInt in this binding while the constant is an Int; the mismatch is real
    if (clock_gettime(CLOCK_PROCESS_CPUTIME_ID.toUInt(), time.ptr) != 0) return null
    time.tv_sec.toDouble() + time.tv_nsec.toDouble() / 1_000_000_000.0
}

/* Top level, not a companion: Kotlin/Native does not allow fields in the companion of a class that
   subclasses an Objective-C type, which NSObject is. */
private const val TAG = "Metrics"

/** Half a `power_arm.sh` run at its shortest, so that every run gets at least one "so far" line
 *  well clear of the kill; and sparse enough that an hour's survey is 120 lines, not thousands. */
private const val STINT_LOG_INTERVAL_SECONDS = 30.0

/** MetricKit hands out [NSMeasurement]s whose unit is not guaranteed, so convert rather than
 *  reading [NSMeasurement.doubleValue] and hoping it is already seconds.
 *
 *  This is the most consequential line in the file: reading the raw value of a measurement that
 *  happened to be in milliseconds would report CPU-per-foreground-second a thousand times too
 *  large, and it would look plausible. Tested in IosMetricsCollectorTest. */
internal fun NSMeasurement?.seconds(): Double? =
    this?.measurementByConvertingToUnit(NSUnitDuration.seconds)?.doubleValue

internal fun fmt(value: Double?): String =
    if (value == null) "?" else ((value * 10).toLong() / 10.0).toString()
