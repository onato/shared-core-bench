package bench.core

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import bench.core.db.ProductDatabase

class AndroidDrivers(
    private val context: Context,
) : DriverFactory {
    override fun open(dbPath: String) = AndroidSqliteDriver(ProductDatabase.Schema, context, dbPath)
}
