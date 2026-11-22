package bench.benchmark

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import bench.core.AndroidDrivers
import bench.core.Core
import bench.core.CoreConfig
import bench.core.DriverFactory
import bench.core.IngestResult

/** Benchmark 7: ingest one generated page of [count] products into a fresh database. */
class IngestWorkload(
    private val context: Context,
    count: Int,
    seed: Long,
) : AutoCloseable {
    private val body = ProductFixtures.page(count, seed)

    // Core has no close(), so the workload keeps the driver to stop connections piling up across iterations.
    private var driver: SqlDriver? = null
    private val drivers: DriverFactory = AndroidDrivers(context)

    fun freshCore(): Core {
        close()
        context.deleteDatabase(DB_NAME)
        val core = Core.create(CoreConfig(dbPath = DB_NAME)) { path -> drivers.open(path).also { driver = it } }
        // The driver opens lazily; reading once creates the schema before any timed ingest.
        core.stats()
        return core
    }

    fun ingestInto(core: Core): IngestResult = core.ingest(FIRST_REQUEST, HTTP_OK, body)

    override fun close() {
        driver?.close()
        driver = null
    }

    private companion object {
        const val DB_NAME = "ingest-benchmark.db"
        const val FIRST_REQUEST = 1L
        const val HTTP_OK = 200
    }
}
