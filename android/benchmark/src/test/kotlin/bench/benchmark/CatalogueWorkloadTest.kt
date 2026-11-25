package bench.benchmark

import bench.core.Query
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CatalogueWorkloadTest {
    private val workload = CatalogueWorkload(RuntimeEnvironment.getApplication(), rows = 5, seed = 1, pageSize = 2)

    @After
    fun closeWorkload() = workload.close()

    @Test
    fun pagingWithoutAFilterVisitsEveryRowOnceInIdOrder() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), workload.pageThrough(Query()))
    }

    @Test
    fun aSearchReadsOnlyTheFirstPage() {
        assertEquals(listOf(1L, 2L), workload.firstPage(Query(text = "Product")))
    }
}
