package bench.android.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import bench.core.DriverFactory
import bench.core.db.ProductDatabase
import java.util.Properties

/** The core's Android driver needs a device; on the JVM the same schema runs on JDBC SQLite. */
val JvmDrivers =
    DriverFactory { dbPath -> JdbcSqliteDriver("jdbc:sqlite:$dbPath", Properties(), ProductDatabase.Schema) }
