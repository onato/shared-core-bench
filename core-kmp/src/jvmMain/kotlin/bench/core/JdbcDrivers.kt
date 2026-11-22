package bench.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import bench.core.db.ProductDatabase
import java.util.Properties

val JdbcDrivers =
    DriverFactory { dbPath ->
        JdbcSqliteDriver("jdbc:sqlite:$dbPath", Properties(), ProductDatabase.Schema)
    }
