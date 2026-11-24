package bench.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC.md boundary micro-benchmarks 1–5; each name ends in the workload's size. */
@RunWith(AndroidJUnit4::class)
class BoundaryBenchmark {
    @get:Rule
    val benchmark = BenchmarkRule()

    @Test
    fun noOp100000Calls() = measure(100_000) { it.noOpCalls() }

    @Test
    fun stringRoundTrip16Bytes() = measure(16) { it.stringRoundTrip().length }

    @Test
    fun stringRoundTrip1024Bytes() = measure(KB) { it.stringRoundTrip().length }

    @Test
    fun stringRoundTrip102400Bytes() = measure(100 * KB) { it.stringRoundTrip().length }

    @Test
    fun bytesIn1024Bytes() = measure(KB) { it.bytesIn() }

    @Test
    fun bytesIn102400Bytes() = measure(100 * KB) { it.bytesIn() }

    @Test
    fun bytesIn1048576Bytes() = measure(KB * KB) { it.bytesIn() }

    @Test
    fun records20() = measure(20) { it.records().size }

    @Test
    fun records200() = measure(200) { it.records().size }

    @Test
    fun records2000() = measure(2_000) { it.records().size }

    @Test
    fun callback10000Calls() = measure(10_000) { it.callbacks() }

    /** Checks the workload returns [size] once at full size, then times it. */
    private fun measure(
        size: Int,
        run: (BoundaryWorkload) -> Int,
    ) {
        val workload = BoundaryWorkload(size)
        assertEquals(size, run(workload))
        benchmark.measureRepeated { run(workload) }
    }

    private companion object {
        const val KB = 1_024
    }
}
