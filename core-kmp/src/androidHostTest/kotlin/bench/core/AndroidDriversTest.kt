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

        core.ingest("""{"products": [{"id": 1, "title": "Essence Mascara", "price": 9.99}]}""".encodeToByteArray())

        assertEquals(CoreStats(productCount = 1), core.stats())
    }
}
