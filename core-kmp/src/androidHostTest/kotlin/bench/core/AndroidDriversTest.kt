package bench.core

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AndroidDriversTest {
    @Test
    fun productsIngestedThroughTheAndroidDriverAreCounted() {
        val drivers = AndroidDrivers(RuntimeEnvironment.getApplication())
        val core = Core.create(CoreConfig(dbPath = "products.db"), drivers)

        val body =
            """
            {"products": [{"id": 1, "title": "Essence Mascara", "description": "Lash mascara", "category": "beauty",
              "brand": "Essence", "price": 9.99, "discountPercentage": 7.5, "rating": 4.5, "stock": 5,
              "thumbnail": "https://cdn.dummyjson.com/1.png", "tags": ["beauty"]}]}
            """
        core.ingest(requestId = 1, status = HTTP_OK, body = body.encodeToByteArray())

        assertEquals(CoreStats(productCount = 1, deletedCount = 0), core.stats())
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
