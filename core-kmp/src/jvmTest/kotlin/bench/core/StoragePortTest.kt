package bench.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StoragePortTest {
    private val port = jdbcTestPort(":memory:")

    @Test
    fun aPortFailureInsideTheTransactionIsStorageFailedAndStoresNoneOfThePage() {
        val failingAfterTheFirstRow =
            object : StoragePort by port {
                override fun upsert(
                    items: List<ProductDetail>,
                    generation: Long,
                ) {
                    port.upsert(items.take(1), generation)
                    error("disk full")
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

    private fun product(id: Int) =
        """
        {"id": $id, "title": "Essence Mascara", "description": "Lash mascara", "category": "beauty",
         "brand": "Essence", "price": 9.99, "discountPercentage": 7.5, "rating": 4.5, "stock": 5,
         "thumbnail": "https://cdn.dummyjson.com/1.png", "tags": ["beauty"]}
        """
}
