package bench.core

import bench.core.db.ProductDatabase

class Core private constructor(
    val config: CoreConfig,
    private val storage: StoragePort,
) {
    private val paging = RemotePaging(config.baseUrl, config.pageSize)

    fun nextFetch(): FetchRequest? = paging.next()

    fun refresh() = paging.restart()

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
        query: Query,
    ): PageResult {
        if (size <= 0) return PageResult.InvalidSize
        // One row past the page tells whether another page follows.
        val rows = storage.query(query, cursor, size + 1)
        val items = rows.take(size)
        return PageResult.Page(items, nextCursor = if (rows.size > size) items.last().id else null)
    }

    fun detail(id: Long): ProductDetail? = storage.get(id)

    fun categories(): List<String> = storage.categories()

    fun stats(): CoreStats = storage.stats()

    // Drivers throw platform exceptions (java.sql.SQLException, android.database.SQLException)
    // with no common supertype, so common code can only catch Exception.
    @Suppress("TooGenericExceptionCaught")
    private fun store(page: PageDecoding.Products): IngestResult =
        try {
            storage.transaction {
                storage.upsert(page.rows, paging.generation)
                if (paging.isLastPage(page.total)) storage.markMissingDeleted(paging.generation)
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
        ): Core = create(config, EmbeddedStorage(ProductDatabase(drivers.open(config.dbPath))))

        fun create(
            config: CoreConfig,
            port: StoragePort,
        ): Core = Core(config, port)
    }
}
