package bench.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundaryWorkloadTest {
    private val workload = BoundaryWorkload(size = 3)

    @Test
    fun noOpCallsAreMadeSizeTimes() {
        assertEquals(3, workload.noOpCalls())
    }

    @Test
    fun aStringOfSizeBytesComesBackUnchanged() {
        assertEquals("xxx", workload.stringRoundTrip())
    }

    @Test
    fun theCoreReceivesEveryByteSent() {
        assertEquals(3, workload.bytesIn())
    }

    @Test
    fun sizeRecordsComeBack() {
        assertEquals(listOf(1L, 2L, 3L), workload.records().map { it.id })
    }

    @Test
    fun theCoreCallsBackSizeTimes() {
        assertEquals(3, workload.callbacks())
    }
}
