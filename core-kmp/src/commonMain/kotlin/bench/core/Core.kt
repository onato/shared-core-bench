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

    private fun store(page: PageDecoding.Products): IngestResult {
        database.productQueries.transaction {
            page.rows.forEach { database.productQueries.upsert(it.id, it.title, it.price) }
        }
        return IngestResult.Decoded(accepted = page.rows.size, rejected = page.rejected)
    }

    companion object {
        fun create(
            config: CoreConfig,
            drivers: DriverFactory,
        ): Core = Core(config, ProductDatabase(drivers.open(config.dbPath)))
    }
}
