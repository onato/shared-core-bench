package bench.core

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class IngestStorageTest {
    private val dbPath = File(Files.createTempDirectory("ingest").toFile(), "products.db").path

    @Test
    fun aFailureMidIngestStoresNoneOfThePage() {
        val core = Core.create(CoreConfig(dbPath = dbPath), JdbcDrivers)
        failInsertsOf(id = 2)

        val result =
            core.ingest(
                requestId = 1,
                status = 200,
                body = """{"products": [${product(1)}, ${product(2)}, ${product(3)}]}""".encodeToByteArray(),
            )

        assertEquals(IngestResult.StorageFailed, result)
        assertEquals(StatsResult.Stats(CoreStats(productCount = 0, deletedCount = 0)), core.stats())
    }

    @Test
    fun aDriverFailureComesBackFromTheEmbeddedStorageAsAStorageError() {
        val port = jdbcTestPort(dbPath)
        failInsertsOf(id = 2)
        val mascara =
            ProductDetail(2, "Mascara", "Lash", "beauty", null, 9.99, 7.5, 4.5, 5, "https://cdn/2.png", listOf())

        val result = port.upsertOne(mascara, generation = 1)

        assertEquals(
            StorageError(
                "org.sqlite.SQLiteException: [SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, " +
                    "causing the SQL statement to abort (simulated storage failure)",
            ),
            result,
        )
    }

    @Test
    fun aQueryTheDriverFailsComesBackFromTheEmbeddedStorageAsAStorageError() {
        val port = jdbcTestPort(dbPath)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { it.createStatement().execute("DROP TABLE product") }

        val result = port.query(Query(), cursor = null, size = 20)

        assertEquals(
            StorageError(
                "org.sqlite.SQLiteException: [SQLITE_ERROR] SQL error or missing database (no such table: product)",
            ),
            result,
        )
    }

    @Test
    fun aGetTheDriverFailsComesBackFromTheEmbeddedStorageAsAStorageError() {
        val port = jdbcTestPort(dbPath)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use {
            it.createStatement().execute("DROP TABLE product_tag")
        }

        val result = port.get(1)

        assertEquals(
            StorageError(
                "org.sqlite.SQLiteException: [SQLITE_ERROR] SQL error or missing database (no such table: product_tag)",
            ),
            result,
        )
    }

    @Test
    fun categoriesTheDriverFailsComeBackFromTheEmbeddedStorageAsAStorageError() {
        val port = jdbcTestPort(dbPath)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { it.createStatement().execute("DROP TABLE product") }

        val result = port.categories()

        assertEquals(
            StorageError(
                "org.sqlite.SQLiteException: [SQLITE_ERROR] SQL error or missing database (no such table: product)",
            ),
            result,
        )
    }

    @Test
    fun statsTheDriverFailsComeBackFromTheEmbeddedStorageAsAStorageError() {
        val port = jdbcTestPort(dbPath)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { it.createStatement().execute("DROP TABLE product") }

        val result = port.stats()

        assertEquals(
            StorageError(
                "org.sqlite.SQLiteException: [SQLITE_ERROR] SQL error or missing database (no such table: product)",
            ),
            result,
        )
    }

    private fun product(id: Int) =
        """
        {"id": $id, "title": "Essence Mascara", "description": "Lash mascara", "category": "beauty",
         "brand": "Essence", "price": 9.99, "discountPercentage": 7.5, "rating": 4.5, "stock": 5,
         "thumbnail": "https://cdn.dummyjson.com/1.png", "tags": ["beauty"]}
        """

    private fun failInsertsOf(id: Int) =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().execute(
                """
                CREATE TRIGGER fail_insert BEFORE INSERT ON product WHEN NEW.id = $id
                BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END
                """,
            )
        }
}
