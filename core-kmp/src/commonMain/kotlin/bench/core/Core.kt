package bench.core

import bench.core.db.ProductDatabase

class Core private constructor(
    val config: CoreConfig,
    private val storage: StoragePort,
    private val calls: PortCalls,
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

    private fun store(page: PageDecoding.Products): IngestResult =
        when (storage.transaction { write(page) }) {
            is StorageError -> {
                IngestResult.StorageFailed
            }

            is Stored -> {
                paging.advance(page.total)
                IngestResult.Decoded(accepted = page.rows.size, rejected = page.rejected)
            }
        }

    private fun write(page: PageDecoding.Products): StorageResult<Unit> {
        val generation = paging.generation
        val upserted =
            when (calls) {
                PortCalls.BATCHED -> storage.upsert(page.rows, generation)
                PortCalls.CHATTY -> page.rows.storeEach { storage.upsertOne(it, generation) }
            }
        return upserted.then {
            if (paging.isLastPage(page.total)) storage.markMissingDeleted(generation) else Stored(Unit)
        }
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
            calls: PortCalls = PortCalls.BATCHED,
        ): Core = Core(config, port, calls)
    }
}
