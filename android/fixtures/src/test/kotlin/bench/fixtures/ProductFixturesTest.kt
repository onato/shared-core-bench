package bench.fixtures

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

    @Test
    fun aLaterPageGeneratesDifferentTitlesFromTheFirst() {
        assertNotEquals(titles(ProductFixtures.page(count = 3, seed = 7)), titles(laterPage()))
    }

    private fun laterPage() = ProductFixtures.page(count = 3, seed = 7, skip = 3, total = 6)

    private fun titles(page: ByteArray) =
        Regex(""""title":"([^"]+)"""")
            .findAll(page.decodeToString())
            .map { it.groupValues[1] }
            .toList()
}
