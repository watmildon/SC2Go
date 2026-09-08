package de.westnordost.streetcomplete.util.logs

import de.westnordost.streetcomplete.data.StreetCompleteDatabaseTestCase
import de.westnordost.streetcomplete.data.logs.LogLevel
import de.westnordost.streetcomplete.data.logs.LogLevel.DEBUG
import de.westnordost.streetcomplete.data.logs.LogLevel.ERROR
import de.westnordost.streetcomplete.data.logs.LogLevel.INFO
import de.westnordost.streetcomplete.data.logs.LogLevel.VERBOSE
import de.westnordost.streetcomplete.data.logs.LogLevel.WARNING
import de.westnordost.streetcomplete.data.logs.LogsController
import de.westnordost.streetcomplete.data.logs.LogsDao
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every accepted log line is a row inserted into the database, so the level filter is not a
 *  cosmetic one: a release measurement of the map produced 13,602 inserts in 210 seconds, landing
 *  while the map was trying to draw.
 *
 *  This cannot be checked by running the app and looking at the log, which is what was tried first:
 *  on a plain launch nothing logs below INFO at all, so a debug build and a release build produce
 *  an identical five rows and the comparison proves nothing. */
class DatabaseLoggerTest : StreetCompleteDatabaseTestCase() {

    @Test fun doesNotWriteBelowTheMinimumLevel() = runBlocking {
        val dao = LogsDao(database)
        val logger = DatabaseLogger(LogsController(dao), minLevel = INFO)

        logger.v("tag", "verbose")
        logger.d("tag", "debug")
        logger.i("tag", "info")
        logger.w("tag", "warning", null)
        logger.e("tag", "error", null)

        val written = dao.awaitAtLeast(3)
        assertEquals(setOf(INFO, WARNING, ERROR), written.map { it.level }.toSet())
        assertEquals(3, written.size, "something below INFO was written: $written")
    }

    @Test fun writesEverythingWhenTheMinimumIsVerbose() = runBlocking {
        val dao = LogsDao(database)
        val logger = DatabaseLogger(LogsController(dao), minLevel = VERBOSE)

        logger.v("tag", "verbose")
        logger.d("tag", "debug")
        logger.i("tag", "info")

        val written = dao.awaitAtLeast(3)
        assertEquals(setOf(VERBOSE, DEBUG, INFO), written.map { it.level }.toSet())
    }

    /** The filter is `level < minLevel`, which is an ordinal comparison, so it is only correct
     *  while the enum stays in order of increasing severity. Reordering it would silently invert
     *  which lines are dropped. */
    @Test fun logLevelsAreDeclaredInOrderOfSeverity() {
        assertEquals(listOf(VERBOSE, DEBUG, INFO, WARNING, ERROR), LogLevel.entries.toList())
        assertTrue(VERBOSE < DEBUG && DEBUG < INFO && INFO < WARNING && WARNING < ERROR)
    }
}

/** DatabaseLogger writes on its own coroutine, so the rows are not there the instant the call
 *  returns. Polls rather than sleeping a fixed time, so the test is neither flaky nor slow. */
private suspend fun LogsDao.awaitAtLeast(count: Int) = run {
    repeat(100) {
        val rows = getAll()
        if (rows.size >= count) return@run rows
        delay(20)
    }
    getAll()
}
