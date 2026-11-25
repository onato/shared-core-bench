package bench.android.core

/**
 * An in-memory [SharedCore] for app tests. [remotePages] is the server's catalogue; a walk started by [refresh]
 * soft-deletes, once it ends, every product it did not see.
 */
class FakeSharedCore(
    stored: List<ProductSummary> = emptyList(),
    var remotePages: List<List<ProductSummary>> = emptyList(),
    details: List<ProductDetail> = emptyList(),
    private val categoryOf: Map<Long, String> = emptyMap(),
) : SharedCore {
    private val products = stored.associateBy { it.id }.toSortedMap()
    private val details = details.associateBy { it.id }
    private var fetched = 0
    private var seenSinceRefresh: MutableSet<Long>? = null
    private val deleted = mutableSetOf<Long>()

    override fun nextFetch(): FetchRequest? {
        if (fetched == remotePages.size) return null
        val page = fetched + 1L
        return FetchRequest(requestId = page, url = "https://fake.test/products?page=$page")
    }

    override fun ingest(
        requestId: Long,
        status: Int,
        body: ByteArray,
    ): IngestResult =
        when {
            requestId != nextFetch()?.requestId -> IngestResult.Failed(IngestFailure.UnknownRequest)
            status !in SUCCESS -> IngestResult.HttpStatus(status)
            else -> storeNextRemotePage()
        }

    private fun storeNextRemotePage(): IngestResult {
        val page = remotePages[fetched++]
        page.forEach { products[it.id] = it }
        deleted -= page.map { it.id }.toSet()
        seenSinceRefresh?.let { seen ->
            seen += page.map { it.id }
            if (fetched == remotePages.size) deleted += products.keys - seen
        }
        return IngestResult.Ingested(accepted = page.size, rejected = 0)
    }

    override fun page(
        cursor: Long?,
        size: Int,
        query: Query,
    ): PageResult {
        val matching =
            products.values.filter {
                it.id !in deleted &&
                    it.title.contains(query.text, ignoreCase = true) &&
                    (query.category == null || categoryOf[it.id] == query.category) &&
                    it.discountedPrice >= (query.minPrice ?: it.discountedPrice) &&
                    it.discountedPrice <= (query.maxPrice ?: it.discountedPrice)
            }
        val sorted = matching.sortedWith(ORDERS.getValue(query.sort))
        val after = if (cursor == null) sorted else sorted.dropWhile { it.id != cursor }.drop(1)
        val items = after.take(size)
        return PageResult.Page(items, nextCursor = if (after.size > size) items.last().id else null)
    }

    override fun detail(id: Long): DetailResult = details[id]?.let(DetailResult::Found) ?: DetailResult.NotFound

    override fun stats(): CoreStats =
        CoreStats(productCount = products.size.toLong(), deletedCount = deleted.size.toLong())

    override fun categories(): List<String> = categoryOf.values.distinct().sorted()

    override fun refresh() {
        fetched = 0
        seenSinceRefresh = mutableSetOf()
    }

    private companion object {
        val SUCCESS = 200..299
        val ORDERS =
            mapOf(
                Sort.Id to compareBy<ProductSummary> { it.id },
                Sort.PriceAsc to compareBy({ it.discountedPrice }, { it.id }),
                Sort.PriceDesc to compareBy({ -it.discountedPrice }, { it.id }),
                Sort.RatingDesc to compareBy({ -it.rating }, { it.id }),
            )
    }
}
