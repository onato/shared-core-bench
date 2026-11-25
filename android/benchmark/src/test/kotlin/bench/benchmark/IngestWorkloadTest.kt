package bench.benchmark

import bench.core.CoreStats
import bench.core.IngestResult
import bench.core.StatsResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class IngestWorkloadTest {
    private val workload = IngestWorkload(RuntimeEnvironment.getApplication(), count = 3, seed = 1)

    @After
    fun closeWorkload() = workload.close()

    @Test
    fun aFreshDatabaseAcceptsEveryGeneratedProduct() {
        assertEquals(IngestResult.Decoded(accepted = 3, rejected = 0), workload.ingestInto(workload.freshCore()))
    }

    @Test
    fun eachFreshCoreStartsFromAnEmptyDatabase() {
        workload.ingestInto(workload.freshCore())

        assertEquals(StatsResult.Stats(CoreStats(productCount = 0, deletedCount = 0)), workload.freshCore().stats())
    }
}
