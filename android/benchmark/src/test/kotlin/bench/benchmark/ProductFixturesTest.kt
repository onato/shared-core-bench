package bench.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProductFixturesTest {
    @Test
    fun theSameSeedGeneratesTheSamePage() {
        assertEquals(
            ProductFixtures.page(count = 3, seed = 7).decodeToString(),
            ProductFixtures.page(count = 3, seed = 7).decodeToString(),
        )
    }

    @Test
    fun aDifferentSeedGeneratesDifferentProducts() {
        assertNotEquals(
            ProductFixtures.page(count = 3, seed = 7).decodeToString(),
            ProductFixtures.page(count = 3, seed = 8).decodeToString(),
        )
    }
}
