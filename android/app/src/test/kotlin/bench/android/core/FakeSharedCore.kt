package bench.android.core

/** An in-memory [SharedCore] for app tests. */
class FakeSharedCore(
    stored: List<ProductSummary> = emptyList(),
    var remotePages: List<List<ProductSummary>> = emptyList(),
    details: List<ProductDetail> = emptyList(),
) : SharedCore {
    private val products = stored.associateBy { it.id }.toSortedMap()
    private val details = details.associateBy { it.id }
    private var fetched = 0

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
        return IngestResult.Ingested(accepted = page.size, rejected = 0)
    }

    override fun page(
        cursor: Long?,
        size: Int,
        query: Query,
    ): PageResult {
        val matching = products.values.filter { it.title.contains(query.text, ignoreCase = true) }
        val after = matching.filter { cursor == null || it.id > cursor }
        val items = after.take(size)
        return PageResult.Page(items, nextCursor = if (after.size > size) items.last().id else null)
    }

    override fun detail(id: Long): ProductDetail? = details[id]

    override fun stats(): CoreStats = CoreStats(productCount = products.size.toLong(), deletedCount = 0)

    override fun refresh() {
        fetched = 0
    }

    private companion object {
        val SUCCESS = 200..299
    }
}
