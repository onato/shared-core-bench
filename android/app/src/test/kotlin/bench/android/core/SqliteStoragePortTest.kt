package bench.android.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import bench.core.CoreStats
import bench.core.ProductDetail
import bench.core.Query
import bench.core.StorageError
import bench.core.Stored
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SqliteStoragePortTest {
    private val port = SqliteStoragePort.open(":memory:")

    @Test
    fun aTransactionWhoseBlockReturnsAStorageErrorKeepsNoneOfItsWritesAndTheNextOneCommits() {
        val failed =
            port.transaction {
                port.upsertOne(product(id = 1), generation = 1)
                StorageError("disk full")
            }
        port.transaction { port.upsertOne(product(id = 2), generation = 1) }

        assertEquals(StorageError("disk full"), failed)
        assertEquals(listOf(null, 2L), listOf(port.storedId(1), port.storedId(2)))
        assertEquals(CoreStats(productCount = 1, deletedCount = 0), port.stats())
    }

    @Test
    fun aDriverFailureComesBackAsAStorageError() {
        val path = File(Files.createTempDirectory("port").toFile(), "products.db").path
        val filePort = SqliteStoragePort.open(path)
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL(
                "CREATE TRIGGER fail_insert BEFORE INSERT ON product BEGIN SELECT RAISE(ABORT, 'disk full'); END",
            )
        }

        val result = filePort.upsertOne(product(id = 1), generation = 1)

        // The JVM's stub android.database.SQLException drops its message; on a device it carries the driver's.
        assertEquals(StorageError("android.database.SQLException"), result)
    }

    @Test
    fun aQueryTheDriverFailsComesBackAsAStorageError() {
        val path = File(Files.createTempDirectory("port").toFile(), "products.db").path
        val filePort = SqliteStoragePort.open(path)
        BundledSQLiteDriver().open(path).use { it.execSQL("DROP TABLE product") }

        val result = filePort.query(Query(), cursor = null, size = 20)

        assertEquals(StorageError("android.database.SQLException"), result)
    }

    @Test
    fun aGetTheDriverFailsComesBackAsAStorageError() {
        val path = File(Files.createTempDirectory("port").toFile(), "products.db").path
        val filePort = SqliteStoragePort.open(path)
        BundledSQLiteDriver().open(path).use { it.execSQL("DROP TABLE product_tag") }

        val result = filePort.get(1)

        assertEquals(StorageError("android.database.SQLException"), result)
    }

    @Test
    fun categoriesTheDriverFailsComeBackAsAStorageError() {
        val path = File(Files.createTempDirectory("port").toFile(), "products.db").path
        val filePort = SqliteStoragePort.open(path)
        BundledSQLiteDriver().open(path).use { it.execSQL("DROP TABLE product") }

        val result = filePort.categories()

        assertEquals(StorageError("android.database.SQLException"), result)
    }

    @Test
    fun aCommitThatFailsKeepsNoneOfTheTransactionsWritesAndTheNextOneCommits() {
        val path = File(Files.createTempDirectory("port").toFile(), "products.db").path
        val filePort = SqliteStoragePort.open(path)
        filePort.transaction { filePort.upsertOne(product(id = 1), generation = 1) }

        val failed =
            BundledSQLiteDriver().open(path).use { reader ->
                // A reader mid-query holds a shared lock, so the port's COMMIT can't take the exclusive one.
                reader.prepare("SELECT id FROM product").use { query ->
                    query.step()
                    filePort.transaction { filePort.upsertOne(product(id = 2), generation = 1) }
                }
            }
        val next = filePort.transaction { filePort.upsertOne(product(id = 3), generation = 1) }

        assertEquals(listOf(StorageError("android.database.SQLException"), Stored(Unit)), listOf(failed, next))
        assertEquals(listOf(1L, null, 3L), listOf(1L, 2L, 3L).map { filePort.storedId(it) })
    }

    private fun SqliteStoragePort.storedId(id: Long) = (get(id) as Stored).value?.id

    private fun product(id: Long) =
        ProductDetail(id, "Mascara", "Lash mascara", "beauty", null, 9.99, 7.5, 4.5, 5, "https://cdn/1.png", listOf())
}
