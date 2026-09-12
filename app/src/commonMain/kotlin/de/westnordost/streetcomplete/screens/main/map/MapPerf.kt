package de.westnordost.streetcomplete.screens.main.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import de.westnordost.streetcomplete.util.ktx.format
import de.westnordost.streetcomplete.util.logs.Log
import kotlin.time.TimeSource

/** Instrumentation for what drawing the map costs.
 *
 *  Panning stutters when the set of *icons* on screen changes - a new kind of quest coming into
 *  view means new images to rasterise and a new icon expression to build, all on the main thread
 *  (see [de.westnordost.streetcomplete.screens.main.map.layers.PinsLayers]). The stutter is short
 *  and happens while the finger is moving, which makes it hard to catch by eye and impossible to
 *  attribute. So measure it instead.
 *
 *  Off by default and free when off: [mark] returns null and [logSince] does nothing, so nothing
 *  is timed and nothing is logged on the path a normal run takes.
 *
 *  Turn it on with `-mapperf YES` as a launch argument (NSUserDefaults reads those), or from the
 *  MapPerf debug screen, which turns it on for itself. */
object MapPerf {
    var enabled: Boolean = false

    /** Whether the icon expression covers every icon seen so far rather than only the ones in view.
     *
     *  Measured and rejected: it does remove the expression rebuilds (45 -> 7 over a fixed pan) but
     *  it makes every recomposition walk every known painter instead of the twenty-odd on screen,
     *  which took painter resolution from 691ms to 23,145ms over the same pan. Left behind the
     *  switch because the measurement is worth being able to repeat. `-keepiconslive YES`. */
    var keepIconsLive: Boolean = false

    /** Whether every pin icon is loaded once, up front, in a scope that does not recompose.
     *
     *  Resolving an icon the first time costs about a millisecond; finding one already loaded costs
     *  about 0.09ms. So the cost worth removing is the first load, and the way to remove it is to
     *  do all of them once rather than to hold more of them open. `-warmicons NO` to compare. */
    var warmIcons: Boolean = true

    /** Whether the icon expression is built once over a fixed set of icons and handed to
     *  [de.westnordost.streetcomplete.screens.main.map.layers.PinsLayers], rather than rebuilt from
     *  whatever is in view.
     *
     *  **Measured and rejected — off by default.** It was aimed at a cost that turned out not to
     *  exist: rebuilding the expression is 5ms across a whole pan. Turning it on made PinsLayers
     *  recompose 37 -> 1657 times over the same pan and took painter resolution from 515ms to
     *  10,057ms. Why it recomposes that much is not understood, which is a second reason not to
     *  ship it. Kept behind `-hoisticons YES` so the measurement can be repeated. */
    var hoistIconExpression: Boolean = false

    /** Cap the map's frame rate, or null for the platform default. `-maxfps 30`.
     *
     *  The first candidate lever for a low-power mode: MapOptions.maximumFps exists in
     *  maplibre-compose 0.15.0 and the app has never passed it. */
    var maxFps: Int? = null

    /** Compass sampling interval in milliseconds, or null for [COMPASS_UPDATE_INTERVAL].
     *  `-compassms 200`. Android and iOS both sample at 33ms, i.e. 30 Hz, whether or not there is
     *  a location marker on screen to rotate. */
    var compassIntervalMs: Int? = null

    /** Drive the *real* main screen along a fixed route without a finger, so a power trace can be
     *  compared between builds and settings. `-autodrive YES`.
     *
     *  Deliberately not gated on BuildConfig.DEBUG, exactly as `mapperf` is not: it is reachable
     *  only by passing a launch argument, which a user cannot do by accident, and gating it would
     *  mean measuring a debug binary - which is 3-5x slower per operation and would make every
     *  number meaningless. */
    var autoDrive: Boolean = false

    /** Location accuracy tier for the main screen's request, by LocationAccuracy name, or null
     *  for the default (High = kCLLocationAccuracyBest). `-gpsaccuracy Balanced`.
     *
     *  For the walk that decides LOW_POWER_PLAN.md C3: whether Balanced (kCLLocationAccuracyHundredMeters)
     *  still delivers fixes a survey can use, and whether it saves anything. */
    var gpsAccuracy: String? = null

    /** Distance filter for the main screen's request in metres, or null for the default (1 m).
     *  `-gpsdistance 5`. Throttles delivery only; the radio is driven by the accuracy tier. */
    var gpsDistanceM: Double? = null

    const val TAG = "MapPerf"

    /** Start timing, or return null if instrumentation is off. */
    fun mark(): TimeSource.Monotonic.ValueTimeMark? =
        if (enabled) TimeSource.Monotonic.markNow() else null

    /** Log how long it has been since [mark], if it is non-null. */
    fun logSince(mark: TimeSource.Monotonic.ValueTimeMark?, what: () -> String) {
        if (mark == null) return
        val ms = mark.elapsedNow().inWholeMicroseconds / 1000.0
        Log.i(TAG, "${what()} — ${ms.format(2)}ms")
    }

    fun log(message: () -> String) {
        if (enabled) Log.i(TAG, message())
    }
}

/** Logs every frame that took longer than [thresholdMs], plus a summary every [reportEvery]
 *  frames.
 *
 *  A long frame is the stutter, directly: the display asks for a frame every ~16.7ms, so anything
 *  much beyond that is a frame the user did not get. Logging the frames themselves rather than
 *  only a summary is what makes it possible to line a stutter up against what the map was doing at
 *  that moment - the icon-expression rebuild logged from PinsLayers appears in the same log,
 *  interleaved.
 *
 *  Note this keeps asking for frames, so while it is on the app renders continuously rather than
 *  only when something changes. That is the point - it is measuring the paced frame loop - but it
 *  does mean the numbers are not a battery measurement. */
@Composable
fun FrameStutterLogger(
    thresholdMs: Double = 24.0,
    reportEvery: Int = 120,
) {
    /* The check is inside the effect on purpose. As an early `return` before it, this composable
       is skippable - its parameters never change - so Compose never re-runs it, and the verdict
       from the first composition (before anything had turned instrumentation on) stood forever
       and no frame was ever measured. */
    LaunchedEffect(Unit) {
        if (!MapPerf.enabled) return@LaunchedEffect
        var previousFrameNanos = 0L
        var frames = 0
        var longFrames = 0
        var worstMs = 0.0
        var totalMs = 0.0
        while (true) {
            withFrameNanos { frameTimeNanos ->
                if (previousFrameNanos != 0L) {
                    val ms = (frameTimeNanos - previousFrameNanos) / 1_000_000.0
                    frames++
                    totalMs += ms
                    if (ms >= thresholdMs) {
                        longFrames++
                        if (ms > worstMs) worstMs = ms
                        Log.w(MapPerf.TAG, "LONG FRAME ${ms.format(1)}ms")
                    }
                    if (frames % reportEvery == 0) {
                        val pct = 100.0 * longFrames / frames
                        Log.i(MapPerf.TAG,
                            "frames=$frames long=$longFrames (${pct.format(1)}%) " +
                            "worst=${worstMs.format(1)}ms avg=${(totalMs / frames).format(1)}ms"
                        )
                    }
                }
                previousFrameNanos = frameTimeNanos
            }
        }
    }
}

/** Logs whenever the main thread was blocked for longer than [thresholdMs].
 *
 *  This, and not [FrameStutterLogger], is the instrument that matches what "sluggish" means. The
 *  Compose frame clock only ticks when Compose itself has something to do, and the map is drawn by
 *  MapLibre in its own layer — so a hundred seconds of panning produced about twenty Compose
 *  frames, with multi-second gaps between them that are idleness, not jank. Measuring those gaps
 *  says nothing about whether the map moved smoothly.
 *
 *  A watchdog does not care who is drawing. It asks to be woken every [tickMs] on the main thread
 *  and reports how late it actually was: if something occupies that thread for 150ms — resolving
 *  icon painters, say — the wake-up is 150ms late, and that is exactly the stall the user feels as
 *  the map failing to keep up with their finger. */
@Composable
fun MainThreadStallLogger(
    thresholdMs: Long = 32,
    tickMs: Long = 8,
) {
    LaunchedEffect(Unit) {
        if (!MapPerf.enabled) return@LaunchedEffect
        /* The waiting is done on a background dispatcher and only the *hop onto* the main thread is
           timed. Sleeping on the main thread instead measures nothing useful here: a LaunchedEffect
           runs in the composition's context, whose delays are paced by Compose's own clock, so a
           quiet period reads as a multi-second stall when in truth nothing was blocked and nothing
           needed drawing. How long it takes to be given a slot on the main thread does not care who
           is drawing or whether Compose has work — if something occupies that thread for 150ms, the
           hop takes 150ms. */
        withContext(Dispatchers.Default) {
            var stalls = 0
            var worstMs = 0L
            var totalStallMs = 0L
            var ticks = 0
            while (true) {
                delay(tickMs)
                val mark = TimeSource.Monotonic.markNow()
                withContext(Dispatchers.Main) { /* just wait to be scheduled */ }
                val waitedMs = mark.elapsedNow().inWholeMilliseconds
                ticks++
                if (waitedMs >= thresholdMs) {
                    stalls++
                    totalStallMs += waitedMs
                    if (waitedMs > worstMs) worstMs = waitedMs
                    Log.w(MapPerf.TAG, "MAIN THREAD BLOCKED ${waitedMs}ms")
                }
                if (ticks % 500 == 0) {
                    Log.i(MapPerf.TAG,
                        "blocked: $stalls times, worst ${worstMs}ms, ${totalStallMs}ms total over $ticks probes"
                    )
                }
            }
        }
    }
}
