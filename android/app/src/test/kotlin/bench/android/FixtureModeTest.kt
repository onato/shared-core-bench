package bench.android

import bench.android.core.KmpSharedCore
import bench.fixtures.ProductFixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The core the app creates pages remotely 20 products at a time.
@RunWith(RobolectricTestRunner::class)
class FixtureModeTest {
    private val core = KmpSharedCore.create(RuntimeEnvironment.getApplication(), dbPath = "products.db")

    @Test
    fun theFirstFetchReturnsTheGeneratorsFirstPageForTheSeed() {
        val fetcher = OkHttpFetcher(fixtureClient(records = 20, seed = 7))

        val response = fetcher.fetch(checkNotNull(core.nextFetch())) as FetchResult.Response

        assertEquals(200, response.status)
        assertEquals(ProductFixtures.page(count = 20, seed = 7).decodeToString(), response.body.decodeToString())
    }

    @Test
    fun pagingStopsAtTheFixtureRecordCount() {
        val fetcher = OkHttpFetcher(fixtureClient(records = 50, seed = 7))

        val countAfterEachPage =
            generateSequence { core.nextFetch() }
                .map { request ->
                    val response = fetcher.fetch(request) as FetchResult.Response
                    core.ingest(request.requestId, response.status, response.body)
                    core.stats().productCount
                }.toList()

        assertEquals(listOf(20L, 40L, 50L), countAfterEachPage)
    }
}
