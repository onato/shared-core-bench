package bench.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import bench.core.db.ProductDatabase
import co.touchlab.sqliter.DatabaseConfiguration

class NativeDrivers : DriverFactory {
    override fun open(dbPath: String) =
        NativeSqliteDriver(
            schema = ProductDatabase.Schema,
            name = dbPath.substringAfterLast('/'),
            onConfiguration = {
                it.copy(extendedConfig = DatabaseConfiguration.Extended(basePath = dbPath.substringBeforeLast('/')))
            },
        )
}
