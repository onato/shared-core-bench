package bench.android.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KmpSharedCoreCreateTest {
    @Test
    fun aCoreCreatedOnAnAndroidContextReadsBackAnIngestedPage() {
        val core = KmpSharedCore.create(RuntimeEnvironment.getApplication(), dbPath = "products.db")
        val request = checkNotNull(core.nextFetch())

        val body =
            """
            {"products": [{"id": 1, "title": "Essence Mascara", "description": "Lash mascara", "category": "beauty",
              "brand": "Essence", "price": 9.99, "discountPercentage": 7.5, "rating": 4.5, "stock": 5,
              "thumbnail": "https://cdn.dummyjson.com/1.png", "tags": ["beauty"]}]}
            """
        core.ingest(request.requestId, status = 200, body = body.encodeToByteArray())

        assertEquals(
            PageResult.Page(
                listOf(
                    ProductSummary(
                        id = 1,
                        title = "Essence Mascara",
                        price = 9.99,
                        discountedPrice = 9.24,
                        rating = 4.5,
                        thumbnail = "https://cdn.dummyjson.com/1.png",
                    ),
                ),
                nextCursor = null,
            ),
            core.page(cursor = null, size = 20),
        )
    }
}
