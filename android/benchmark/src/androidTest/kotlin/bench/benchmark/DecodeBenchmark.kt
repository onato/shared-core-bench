package bench.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import bench.core.IngestResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC.md benchmark 6; each name ends in the number of products decoded. */
@RunWith(AndroidJUnit4::class)
class DecodeBenchmark {
    @get:Rule
    val benchmark = BenchmarkRule()

    @Test
    fun decode100Products() = measure(100)

    @Test
    fun decode1000Products() = measure(1_000)

    @Test
    fun decode10000Products() = measure(10_000)

    private fun measure(products: Int) {
        val workload = DecodeWorkload(products, SEED)
        assertEquals(IngestResult.Decoded(products, rejected = 0), workload.decode())
        benchmark.measureRepeated { workload.decode() }
    }

    private companion object {
        const val SEED = 17L
    }
}
