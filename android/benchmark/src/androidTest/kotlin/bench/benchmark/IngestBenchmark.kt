package bench.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import bench.core.IngestResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC.md benchmark 7; each name ends in the number of products ingested. */
@RunWith(AndroidJUnit4::class)
class IngestBenchmark {
    @get:Rule
    val benchmark = BenchmarkRule()

    private val cores = benchCores()

    @Test
    fun ingest100Products() = measure(100)

    @Test
    fun ingest1000Products() = measure(1_000)

    @Test
    fun ingest10000Products() = measure(10_000)

    private fun measure(products: Int) =
        IngestWorkload(cores, products, SEED).use { workload ->
            assertEquals(IngestResult.Decoded(products, rejected = 0), workload.ingestInto(workload.freshCore()))
            benchmark.measureRepeated {
                val core = runWithMeasurementDisabled { workload.freshCore() }
                workload.ingestInto(core)
            }
        }

    private companion object {
        const val SEED = 17L
    }
}
