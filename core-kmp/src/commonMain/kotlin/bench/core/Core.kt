package bench.core

import bench.core.db.ProductDatabase

class Core private constructor(
    val config: CoreConfig,
    private val database: ProductDatabase,
) {
    fun ingest(body: ByteArray): IngestResult =
        when (val page = decodePage(body)) {
            is PageDecoding.Unreadable -> page.error
            is PageDecoding.Products -> store(page)
        }

    fun stats(): CoreStats = CoreStats(productCount = database.productQueries.count().executeAsOne())

    // Drivers throw platform exceptions (java.sql.SQLException, android.database.SQLException)
    // with no common supertype, so common code can only catch Exception.
    @Suppress("TooGenericExceptionCaught")
    private fun store(page: PageDecoding.Products): IngestResult =
        try {
            database.productQueries.transaction {
                page.rows.forEach { database.productQueries.upsert(it.id, it.title, it.price) }
            }
            IngestResult.Decoded(accepted = page.rows.size, rejected = page.rejected)
        } catch (_: Exception) {
            IngestResult.StorageFailed
        }

    companion object {
        fun create(
            config: CoreConfig,
            drivers: DriverFactory,
        ): Core = Core(config, ProductDatabase(drivers.open(config.dbPath)))
    }
}
