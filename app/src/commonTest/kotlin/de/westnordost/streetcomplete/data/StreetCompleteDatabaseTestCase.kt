package de.westnordost.streetcomplete.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest

open class StreetCompleteDatabaseTestCase {
    private var connection: SQLiteConnection? = null
    private var _database: Database? = null

    /** Created when it is first asked for, rather than in a `@BeforeTest`.
     *
     *  On Kotlin/Native a subclass's `@BeforeTest` runs *before* the one it inherits, the opposite
     *  way round from JVM. Every test case here builds its DAO from this database in its own
     *  `@BeforeTest`, so with a `@BeforeTest` here they would all have run against a database that
     *  did not exist yet - and the failure was hidden, because tearDown then failed too and its
     *  error is the one reported. Creating it on demand takes the ordering out of it entirely. */
    protected val database: Database get() = _database ?: run {
        SystemFileSystem.delete(Path(DATABASE_NAME), mustExist = false)
        val newConnection = BundledSQLiteDriver().open(DATABASE_NAME)
        connection = newConnection
        DatabaseImpl(newConnection).also {
            it.initialize(StreetCompleteDatabaseConfigurator)
            _database = it
        }
    }

    @AfterTest fun tearDown() {
        connection?.close()
        connection = null
        _database = null
        SystemFileSystem.delete(Path(DATABASE_NAME), mustExist = false)
    }

    companion object {
        private const val DATABASE_NAME = "streetcomplete_test.db"
    }
}
