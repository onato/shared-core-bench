package bench.benchmark

import bench.core.IngestResult
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeWorkloadTest {
    @Test
    fun everyGeneratedProductDecodes() {
        assertEquals(IngestResult.Decoded(accepted = 3, rejected = 0), DecodeWorkload(count = 3, seed = 1).decode())
    }
}
