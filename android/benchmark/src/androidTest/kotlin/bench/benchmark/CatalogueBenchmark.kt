package bench.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import bench.core.Query
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC.md benchmarks 8 and 9 on a database of [ROWS] products; each name starts with that row count. */
@RunWith(AndroidJUnit4::class)
class CatalogueBenchmark {
    @get:Rule
    val benchmark = BenchmarkRule()

    private val workload =
        CatalogueWorkload(InstrumentationRegistry.getInstrumentation().targetContext, ROWS, SEED, PAGE_SIZE)

    @After
    fun closeWorkload() = workload.close()

    @Test
    fun page10000RowsBy20() {
        assertEquals(ROWS, workload.pageThrough(Query()).size)
        benchmark.measureRepeated { workload.pageThrough(Query()) }
    }

    @Test
    fun page10000RowsBy20WithSearch() {
        val matches = workload.pageThrough(SEARCH)
        assertEquals(matches.distinct(), matches)
        benchmark.measureRepeated { workload.pageThrough(SEARCH) }
    }

    @Test
    fun search10000Rows() {
        assertEquals(PAGE_SIZE, workload.firstPage(SEARCH).size)
        benchmark.measureRepeated { workload.firstPage(SEARCH) }
    }

    private companion object {
        const val ROWS = 10_000
        const val PAGE_SIZE = 20
        const val SEED = 17L

        // Generated titles are "Product <random number>", so this keeps those whose number starts with 1.
        val SEARCH = Query(text = "Product 1")
    }
}
