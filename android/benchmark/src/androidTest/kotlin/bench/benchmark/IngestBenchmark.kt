package bench.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import bench.core.IngestResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IngestBenchmark {
    @get:Rule
    val benchmark = BenchmarkRule()

    private val workload =
        IngestWorkload(InstrumentationRegistry.getInstrumentation().targetContext, count = PRODUCTS, seed = SEED)

    @After
    fun closeWorkload() = workload.close()

    @Test
    fun ingest1000Products() {
        assertEquals(IngestResult.Decoded(accepted = PRODUCTS, rejected = 0), workload.ingestInto(workload.freshCore()))
        benchmark.measureRepeated {
            val core = runWithMeasurementDisabled { workload.freshCore() }
            workload.ingestInto(core)
        }
    }

    private companion object {
        const val PRODUCTS = 1_000
        const val SEED = 17L
    }
}
