package bench.android.core

import bench.core.CoreStats
import bench.core.ProductDetail
import org.junit.Assert.assertEquals
import org.junit.Test

class SqliteStoragePortTest {
    private val port = SqliteStoragePort.open(":memory:")

    @Test
    fun aTransactionWhoseBlockThrowsKeepsNoneOfItsWritesAndTheNextOneCommits() {
        runCatching {
            port.transaction {
                port.upsertOne(product(id = 1), generation = 1)
                error("disk full")
            }
        }
        port.transaction { port.upsertOne(product(id = 2), generation = 1) }

        assertEquals(listOf(null, 2L), listOf(port.get(1)?.id, port.get(2)?.id))
        assertEquals(CoreStats(productCount = 1, deletedCount = 0), port.stats())
    }

    private fun product(id: Long) =
        ProductDetail(id, "Mascara", "Lash mascara", "beauty", null, 9.99, 7.5, 4.5, 5, "https://cdn/1.png", listOf())
}
