package bench.benchmark

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import bench.core.AndroidDrivers
import bench.core.Core
import bench.core.CoreConfig
import bench.core.DriverFactory

/** Cores on the core's own SQLDelight database. */
class EmbeddedCores(
    private val context: Context,
) : FreshCores {
    // Core has no close(), so this keeps the driver to stop connections piling up across iterations.
    private var driver: SqlDriver? = null
    private val drivers: DriverFactory = AndroidDrivers(context)

    override fun freshCore(): Core {
        close()
        context.deleteDatabase(DB_NAME)
        val core = Core.create(CoreConfig(dbPath = DB_NAME)) { path -> drivers.open(path).also { driver = it } }
        // The driver opens lazily; reading once creates the schema before any timed ingest.
        core.stats()
        return core
    }

    override fun close() {
        driver?.close()
        driver = null
    }

    private companion object {
        const val DB_NAME = "ingest-benchmark.db"
    }
}
