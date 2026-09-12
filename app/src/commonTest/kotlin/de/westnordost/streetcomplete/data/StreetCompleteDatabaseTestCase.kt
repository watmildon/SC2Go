package de.westnordost.streetcomplete.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** Base class for tests that need a real database.
 *
 *  Subclasses must not set themselves up in their own `@BeforeTest`: on Kotlin/Native a subclass's
 *  `@BeforeTest` runs *before* the one it inherits, the opposite way round from JVM, so a DAO built
 *  there would be built against a database that does not exist yet - and the failure was hidden,
 *  because tearDown then failed too and its error is the one reported. Override
 *  [onDatabaseInitialized] instead; it is called once the database is ready.
 *
 *  Fork delta against upstream: the class is `open` rather than `abstract`, [onDatabaseInitialized]
 *  has an empty default body and [database] is `protected`, so that test cases which only need a
 *  database inside the test body (DatabaseLoggerTest) do not have to implement the callback. */
open class StreetCompleteDatabaseTestCase {
    protected lateinit var database: Database
    private lateinit var connection: SQLiteConnection

    @BeforeTest fun setUp() {
        SystemFileSystem.delete(Path(DATABASE_NAME), mustExist = false)
        connection = BundledSQLiteDriver().open(DATABASE_NAME)
        database = DatabaseImpl(connection)
        database.initialize(StreetCompleteDatabaseConfigurator)
        onDatabaseInitialized(database)
    }

    open fun onDatabaseInitialized(database: Database) {}

    @AfterTest fun tearDown() {
        connection.close()
        SystemFileSystem.delete(Path(DATABASE_NAME), mustExist = false)
    }

    companion object {
        private const val DATABASE_NAME = "streetcomplete_test.db"
    }
}
