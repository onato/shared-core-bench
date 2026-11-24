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
        query: Query,
    ): PageResult {
        if (size <= 0) return PageResult.InvalidSize
        // One row past the page tells whether another page follows.
        val rows =
            database.productQueries
                .pageAfter(
                    cursor = cursor,
                    text = query.text,
                    category = query.category,
                    minPrice = query.minPrice,
                    maxPrice = query.maxPrice,
                    sort = query.sort.key,
                    limit = size + 1L,
                ) { id, title, price, discountedPrice, rating, thumbnail ->
                    ProductSummary(id, title, price, discountedPrice, rating, thumbnail)
                }.executeAsList()
        val items = rows.take(size)
        return PageResult.Page(items, nextCursor = if (rows.size > size) items.last().id else null)
    }

    fun detail(id: Long): ProductDetail? {
        val queries = database.productQueries
        val tags = queries.tagsOf(id).executeAsList()
        return queries
            .byId(id) { id, title, description, category, brand, price, discount, rating, stock, thumbnail ->
                ProductDetail(id, title, description, category, brand, price, discount, rating, stock, thumbnail, tags)
            }.executeAsOneOrNull()
    }

    fun stats(): CoreStats = CoreStats(productCount = database.productQueries.count().executeAsOne())

    // Drivers throw platform exceptions (java.sql.SQLException, android.database.SQLException)
    // with no common supertype, so common code can only catch Exception.
    @Suppress("TooGenericExceptionCaught")
    private fun store(page: PageDecoding.Products): IngestResult =
        try {
            database.productQueries.transaction {
                page.rows.forEach(::upsert)
            }
            paging.advance(page.total)
            IngestResult.Decoded(accepted = page.rows.size, rejected = page.rejected)
        } catch (_: Exception) {
            IngestResult.StorageFailed
        }

    private fun upsert(product: ProductDetail) =
        with(product) {
            database.productQueries.upsert(
                id,
                title,
                description,
                category,
                brand,
                price,
                discountPercentage,
                discountedPrice,
                rating,
                stock,
                thumbnail,
            )
            database.productQueries.deleteTagsOf(id)
            tags.forEachIndexed { position, tag -> database.productQueries.insertTag(id, position.toLong(), tag) }
        }

    companion object {
        private val SUCCESS = 200..299

        fun create(
            config: CoreConfig,
            drivers: DriverFactory,
        ): Core = Core(config, ProductDatabase(drivers.open(config.dbPath)))
    }
}
