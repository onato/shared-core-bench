package bench.android.core

import bench.core.Core
import bench.core.CoreConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

class KmpSharedCoreTest {
    private val dbPath = File(Files.createTempDirectory("kmp-shared-core").toFile(), "products.db").path

    @Test
    fun aStorageFailureInTheCoreReachesTheAppAsAFailedIngest() {
        val core = KmpSharedCore(Core.create(CoreConfig(dbPath = dbPath), JvmDrivers))
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().execute(
                "CREATE TRIGGER fail_insert BEFORE INSERT ON product BEGIN SELECT RAISE(ABORT, 'disk full'); END",
            )
        }

        val body =
            """
            {"products": [{"id": 1, "title": "Mascara", "description": "Lash mascara", "category": "beauty",
              "brand": "Essence", "price": 9.99, "discountPercentage": 7.5, "rating": 4.5, "stock": 5,
              "thumbnail": "https://cdn.dummyjson.com/1.png", "tags": ["beauty"]}]}
            """
        val result = core.ingest(requestId = 1, status = 200, body = body.encodeToByteArray())

        assertEquals(IngestResult.Failed(IngestFailure.StorageFailed), result)
    }

    @Test
    fun aStorageFailureInTheCoreReachesTheAppAsAFailedPage() {
        val core = KmpSharedCore(Core.create(CoreConfig(dbPath = dbPath), JvmDrivers))
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { it.createStatement().execute("DROP TABLE product") }

        assertEquals(PageResult.StorageFailed, core.page(cursor = null, size = 20))
    }

    @Test
    fun aStorageFailureInTheCoreReachesTheAppAsAFailedDetail() {
        val core = KmpSharedCore(Core.create(CoreConfig(dbPath = dbPath), JvmDrivers))
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use {
            it.createStatement().execute("DROP TABLE product_tag")
        }

        assertEquals(DetailResult.StorageFailed, core.detail(id = 1))
    }

    @Test
    fun aStorageFailureInTheCoreReachesTheAppAsFailedCategories() {
        val core = KmpSharedCore(Core.create(CoreConfig(dbPath = dbPath), JvmDrivers))
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { it.createStatement().execute("DROP TABLE product") }

        assertEquals(CategoriesResult.StorageFailed, core.categories())
    }
}
