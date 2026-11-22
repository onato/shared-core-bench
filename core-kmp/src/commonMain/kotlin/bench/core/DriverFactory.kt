package bench.core

import app.cash.sqldelight.db.SqlDriver

/** Opens the platform's SQLite driver at `dbPath`, creating the product schema if the database is new. */
fun interface DriverFactory {
    fun open(dbPath: String): SqlDriver
}
