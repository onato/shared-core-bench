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
                body =
                    """
                {"products": [
                  {"id": 1, "title": "Essence Mascara", "price": 9.99},
                  {"id": 2, "title": "Eyeshadow Palette", "price": 19},
                  {"id": 3, "title": "Powder Canister", "price": 14.99}
                ]}
                """.encodeToByteArray(),
            )

        assertEquals(IngestResult.StorageFailed, result)
        assertEquals(CoreStats(productCount = 0), core.stats())
    }

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
