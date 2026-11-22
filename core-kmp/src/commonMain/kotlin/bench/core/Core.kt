package bench.core

import bench.core.db.ProductDatabase

class Core private constructor(
    val config: CoreConfig,
    private val database: ProductDatabase,
) {
    private val paging = RemotePaging(config.baseUrl, config.pageSize)

    fun nextFetch(): FetchRequest? = paging.next()

    fun ingest(
        requestId: Long,
        status: Int,
        body: ByteArray,
    ): IngestResult =
        when {
            !paging.isPending(requestId) -> IngestResult.UnknownRequest
            status !in SUCCESS -> IngestResult.HttpStatus(status)
            else -> decodeAndStore(body)
        }

    private fun decodeAndStore(body: ByteArray): IngestResult =
        when (val page = decodePage(body)) {
            is PageDecoding.Unreadable -> page.error
            is PageDecoding.Products -> store(page)
        }

    fun page(
        cursor: Long?,
        size: Int,
    ): PageResult {
        if (size <= 0) return PageResult.InvalidSize
        // One row past the page tells whether another page follows.
        val rows = database.productQueries.pageAfter(cursor ?: Long.MIN_VALUE, size + 1L, ::Product).executeAsList()
        val items = rows.take(size)
        return PageResult.Page(items, nextCursor = if (rows.size > size) items.last().id else null)
    }

    fun detail(id: Long): Product? = database.productQueries.byId(id, ::Product).executeAsOneOrNull()

    fun stats(): CoreStats = CoreStats(productCount = database.productQueries.count().executeAsOne())

    // Drivers throw platform exceptions (java.sql.SQLException, android.database.SQLException)
    // with no common supertype, so common code can only catch Exception.
    @Suppress("TooGenericExceptionCaught")
    private fun store(page: PageDecoding.Products): IngestResult =
        try {
            database.productQueries.transaction {
                page.rows.forEach { database.productQueries.upsert(it.id, it.title, it.price) }
            }
            paging.advance(page.total)
            IngestResult.Decoded(accepted = page.rows.size, rejected = page.rejected)
        } catch (_: Exception) {
            IngestResult.StorageFailed
        }

    companion object {
        private val SUCCESS = 200..299

        fun create(
            config: CoreConfig,
            drivers: DriverFactory,
        ): Core = Core(config, ProductDatabase(drivers.open(config.dbPath)))
    }
}
