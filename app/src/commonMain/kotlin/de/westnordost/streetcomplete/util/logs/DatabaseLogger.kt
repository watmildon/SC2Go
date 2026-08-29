package de.westnordost.streetcomplete.util.logs

import de.westnordost.streetcomplete.data.logs.LogLevel
import de.westnordost.streetcomplete.data.logs.LogLevel.*
import de.westnordost.streetcomplete.data.logs.LogMessage
import de.westnordost.streetcomplete.data.logs.LogsController
import de.westnordost.streetcomplete.util.ktx.nowAsEpochMilliseconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DatabaseLogger(private val logsController: LogsController) : Logger {
    private val coroutineScope = CoroutineScope(
        SupervisorJob() + CoroutineName("DatabaseLogger") + Dispatchers.IO +
        /* if writing a log to the database fails, there is nothing useful left to do about it -
           and definitely not log it. Without this, the failure is unhandled, which on
           Kotlin/Native takes down the whole app: logging must never be able to do that. */
        CoroutineExceptionHandler { _, _ -> }
    )

    override fun v(tag: String, message: String) {
        log(VERBOSE, tag, message)
    }

    override fun d(tag: String, message: String) {
        log(DEBUG, tag, message)
    }

    override fun i(tag: String, message: String) {
        log(INFO, tag, message)
    }

    override fun w(tag: String, message: String, exception: Throwable?) {
        log(WARNING, tag, message, exception)
    }

    override fun e(tag: String, message: String, exception: Throwable?) {
        log(ERROR, tag, message, exception)
    }

    private fun log(level: LogLevel, tag: String, message: String, exception: Throwable? = null) {
        coroutineScope.launch {
            logsController.add(LogMessage(
                level = level,
                tag = tag,
                message = message,
                error = exception?.toString(),
                timestamp = nowAsEpochMilliseconds()
            ))
        }
    }
}
