package bench.benchmark

import bench.core.CoreStats
import bench.core.IngestResult
import bench.core.PortCalls
import bench.core.StatsResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

// Plain JUnit: the port's bundled SQLite can't load under Robolectric (android/README.md).
class PortCoresTest {
    private val path = File(Files.createTempDirectory("port-cores").toFile(), "products.db").path

    @Test
    fun ingestOnTheBatchedPortAcceptsEveryGeneratedProduct() {
        IngestWorkload(PortCores(path, PortCalls.BATCHED), count = 3, seed = 1).use { workload ->
            assertEquals(IngestResult.Decoded(accepted = 3, rejected = 0), workload.ingestInto(workload.freshCore()))
        }
    }

    @Test
    fun ingestOnTheChattyPortAcceptsEveryGeneratedProduct() {
        IngestWorkload(PortCores(path, PortCalls.CHATTY), count = 3, seed = 1).use { workload ->
            assertEquals(IngestResult.Decoded(accepted = 3, rejected = 0), workload.ingestInto(workload.freshCore()))
        }
    }

    @Test
    fun eachFreshCoreOnThePortStartsFromAnEmptyDatabase() {
        IngestWorkload(PortCores(path, PortCalls.BATCHED), count = 3, seed = 1).use { workload ->
            workload.ingestInto(workload.freshCore())

            assertEquals(StatsResult.Stats(CoreStats(productCount = 0, deletedCount = 0)), workload.freshCore().stats())
        }
    }

    @Test
    fun aFreshCoreOnThePortClosesThePortOfTheCoreBeforeIt() {
        PortCores(path, PortCalls.BATCHED).use { cores ->
            val earlier = cores.freshCore()
            cores.freshCore()

            assertEquals(StatsResult.StorageFailed, earlier.stats())
        }
    }
}
