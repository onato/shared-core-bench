package bench.core

import app.cash.sqldelight.db.SqlDriver

/** Opens the platform's SQLite driver on a database whose schema already exists. */
fun interface DriverFactory {
    fun open(dbPath: String): SqlDriver
}
