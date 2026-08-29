package de.westnordost.streetcomplete

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate

/** When the binary that is running was linked, e.g. "14:32:07".
 *
 *  Deliberately read from the binary rather than baked in at build time: what goes stale is Xcode
 *  linking an old framework, in which case a compiled-in value would be stale along with it and
 *  claim to be fresh. It also costs nothing to build - a value that changed every build would
 *  invalidate BuildKonfig and force a full Kotlin/Native recompile every time. */
@OptIn(ExperimentalForeignApi::class)
private fun buildStamp(): String {
    val path = NSBundle.mainBundle.executablePath ?: return "?"
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
    val date = attributes?.get(NSFileModificationDate) as? NSDate ?: return "?"
    return NSDateFormatter().apply { dateFormat = "HH:mm:ss" }.stringFromDate(date)
}

/** Shows when this build was linked, on top of [content].
 *
 *  Only in debug builds, and only on iOS: it is there to make it obvious at a glance when the
 *  simulator or device is running an older build than the one just made, which Xcode does
 *  silently. Compare it against the time the build finished. */
@OptIn(ExperimentalNativeApi::class)
@Composable
fun WithBuildStamp(content: @Composable () -> Unit) {
    // not BuildConfig.DEBUG: that is off unless the build is told otherwise, whereas this is a
    // property of the binary, and it is debug binaries that get accidentally left behind
    if (!Platform.isDebugBinary) {
        content()
        return
    }
    val stamp = remember { buildStamp() }
    Box(Modifier.fillMaxSize()) {
        content()
        Text(
            text = "build $stamp",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeContentPadding()
                .background(Color(0x99000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
            color = Color.White,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}
