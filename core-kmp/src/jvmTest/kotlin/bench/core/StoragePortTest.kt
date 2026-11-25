package bench.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StoragePortTest {
    private val port = jdbcTestPort(":memory:")

    @Test
    fun anUpsertThatReturnsAStorageErrorIsStorageFailedAndStoresNoneOfThePage() {
        val failingAfterTheFirstRow =
            object : StoragePort by port {
                override fun upsert(
                    items: List<ProductDetail>,
                    generation: Long,
                ): StorageResult<Unit> {
                    port.upsert(items.take(1), generation)
                    return StorageError("disk full")
                }
            }
        val core = Core.create(CoreConfig(dbPath = ":memory:"), failingAfterTheFirstRow)

        val result =
            core.ingest(
                requestId = 1,
                status = 200,
                body = """{"products": [${product(1)}, ${product(2)}]}""".encodeToByteArray(),
            )

        assertEquals(IngestResult.StorageFailed, result)
        assertEquals(CoreStats(productCount = 0, deletedCount = 0), core.stats())
    }

    @Test
    fun aCommitThatFailsIsStorageFailedAndLeavesTheRequestPending() {
        val failingItsCommit =
            object : StoragePort by port {
                override fun transaction(block: () -> StorageResult<Unit>): StorageResult<Unit> {
                    block()
                    return StorageError("disk full")
                }
            }
        val core = Core.create(CoreConfig(dbPath = ":memory:"), failingItsCommit)

        val result =
            core.ingest(requestId = 1, status = 200, body = """{"products": [${product(1)}]}""".encodeToByteArray())

        assertEquals(IngestResult.StorageFailed, result)
        assertEquals(1L, core.nextFetch()?.requestId)
    }

    @Test
    fun aQueryThatReturnsAStorageErrorIsAStorageFailedPage() {
        val failingItsQuery =
            object : StoragePort by port {
                override fun query(
                    query: Query,
                    cursor: Long?,
                    size: Int,
                ): StorageResult<List<ProductSummary>> = StorageError("disk full")
            }
        val core = Core.create(CoreConfig(dbPath = ":memory:"), failingItsQuery)

        assertEquals(PageResult.StorageFailed, core.page(cursor = null, size = 20, query = Query()))
    }

    @Test
    fun aGetThatReturnsAStorageErrorIsAStorageFailedDetail() {
        val failingItsGet =
            object : StoragePort by port {
                override fun get(id: Long): StorageResult<ProductDetail?> = StorageError("disk full")
            }
        val core = Core.create(CoreConfig(dbPath = ":memory:"), failingItsGet)

        assertEquals(DetailResult.StorageFailed, core.detail(id = 1))
    }

    @Test
    fun categoriesThatReturnAStorageErrorAreStorageFailedCategories() {
        val failingItsCategories =
            object : StoragePort by port {
                override fun categories(): StorageResult<List<String>> = StorageError("disk full")
            }
        val core = Core.create(CoreConfig(dbPath = ":memory:"), failingItsCategories)

        assertEquals(CategoriesResult.StorageFailed, core.categories())
    }

    @Test
    fun ingestingAPageOf20ThroughTheChattyVariantMakes20UpsertOneCallsAndNoUpsertCall() {
        val calls = mutableListOf<String>()
        val counting =
            object : StoragePort by port {
                override fun upsert(
                    items: List<ProductDetail>,
                    generation: Long,
                ): StorageResult<Unit> {
                    calls += "upsert"
                    return port.upsert(items, generation)
                }

                override fun upsertOne(
                    item: ProductDetail,
                    generation: Long,
                ): StorageResult<Unit> {
                    calls += "upsertOne"
                    return port.upsertOne(item, generation)
                }
            }
        val core = Core.create(CoreConfig(dbPath = ":memory:"), counting, PortCalls.CHATTY)

        core.ingest(
            requestId = 1,
            status = 200,
            body = """{"products": [${(1..20).joinToString { product(it) }}], "total": 20}""".encodeToByteArray(),
        )

        assertEquals(List(20) { "upsertOne" }, calls)
    }

    private fun product(id: Int) =
        """
        {"id": $id, "title": "Essence Mascara", "description": "Lash mascara", "category": "beauty",
         "brand": "Essence", "price": 9.99, "discountPercentage": 7.5, "rating": 4.5, "stock": 5,
         "thumbnail": "https://cdn.dummyjson.com/1.png", "tags": ["beauty"]}
        """
}
