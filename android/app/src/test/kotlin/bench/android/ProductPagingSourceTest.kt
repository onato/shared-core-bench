package bench.android

import androidx.paging.PagingConfig
import androidx.paging.PagingSource.LoadResult
import androidx.paging.testing.TestPager
import bench.android.core.FakeSharedCore
import bench.android.core.FetchRequest
import bench.android.core.summary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductPagingSourceTest {
    private val mascara = summary(id = 1, title = "Essence Mascara", price = 9.99)
    private val palette = summary(id = 2, title = "Eyeshadow Palette", price = 19.0)
    private val canister = summary(id = 3, title = "Powder Canister", price = 14.99)
    private val lipstick = summary(id = 4, title = "Red Lipstick", price = 12.99)

    private val fetched = mutableListOf<FetchRequest>()
    private val responses = ArrayDeque<FetchResult>()

    private fun fetch(request: FetchRequest): FetchResult {
        fetched += request
        return responses.removeFirstOrNull() ?: FetchResult.Response(status = 200, body = ByteArray(0))
    }

    private fun remotePage(page: Int) = "https://fake.test/products?page=$page"

    private val config = PagingConfig(pageSize = 2, initialLoadSize = 2)

    private fun pager(core: FakeSharedCore) = TestPager(config, ProductPagingSource(core, ::fetch))

    @Test
    fun firstLoadReturnsTheFirstPageFromTheCore() =
        runTest {
            val pager = pager(FakeSharedCore(stored = listOf(canister, mascara, palette)))

            val first = pager.refresh()

            assertEquals(LoadResult.Page(listOf(mascara, palette), prevKey = null, nextKey = 2L), first)
        }

    @Test
    fun appendContinuesFromTheNextCursor() =
        runTest {
            val pager = pager(FakeSharedCore(stored = listOf(canister, mascara, palette)))
            pager.refresh()

            val second = pager.append()

            assertEquals(LoadResult.Page(listOf(canister), prevKey = null, nextKey = null), second)
        }

    @Test
    fun runningOutOfLocalProductsFetchesAndIngestsTheNextRemotePage() =
        runTest {
            val core = FakeSharedCore(stored = listOf(mascara), remotePages = listOf(listOf(palette), listOf(canister)))
            val pager = pager(core)

            val first = pager.refresh()
            val second = pager.append()

            assertEquals(LoadResult.Page(listOf(mascara, palette), prevKey = null, nextKey = 2L), first)
            assertEquals(LoadResult.Page(listOf(canister), prevKey = null, nextKey = null), second)
            assertEquals(listOf(remotePage(1), remotePage(2)), fetched.map { it.url })
        }

    @Test
    fun fetchErrorsSurfaceAsLoadErrorsAndRetryRepeatsTheSameRequest() =
        runTest {
            val core =
                FakeSharedCore(stored = listOf(mascara, palette, canister), remotePages = listOf(listOf(lipstick)))
            val pager = pager(core)
            responses += FetchResult.NetworkError("timeout")
            responses += FetchResult.Response(status = 503, body = ByteArray(0))
            pager.refresh()

            val networkError = pager.append()
            val httpError = pager.append()
            val recovered = pager.append()

            assertEquals("Network error: timeout", (networkError as LoadResult.Error).throwable.message)
            assertEquals(
                "Remote load failed: HttpStatus(status=503)",
                (httpError as LoadResult.Error).throwable.message,
            )
            assertEquals(LoadResult.Page(listOf(canister, lipstick), prevKey = null, nextKey = null), recovered)
            assertEquals(List(3) { remotePage(1) }, fetched.map { it.url })
        }

    @Test
    fun refreshingAfterScrollingStartsAgainFromTheFirstProduct() =
        runTest {
            val source = ProductPagingSource(FakeSharedCore(stored = listOf(canister, mascara, palette)), ::fetch)
            val pager = TestPager(config, source)
            pager.refresh()
            pager.append()

            val refreshKey = source.getRefreshKey(pager.getPagingState(anchorPosition = 2))

            assertEquals(null, refreshKey)
        }
}
