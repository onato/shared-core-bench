package bench.android.core

import bench.android.CoreChoice
import bench.android.CoreKind
import bench.android.FetchResult
import bench.android.OkHttpFetcher
import bench.android.Storage
import bench.android.fixtureClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// Embedded only: the bundled SQLite's native library loads into one classloader, and Robolectric's is not the
// one the port's host tests use.
@RunWith(RobolectricTestRunner::class)
class AndroidCoresCreateTest {
    private val embedded = CoreChoice(CoreKind.Kmp, Storage.Embedded)

    @Test
    fun theAppsCoresKeepTheirProductsAcrossALaunch() {
        val core = AndroidCores.create(RuntimeEnvironment.getApplication()).open(embedded, empty = false)
        val request = checkNotNull(core.nextFetch())
        val response = OkHttpFetcher(fixtureClient(records = 20, seed = 7)).fetch(request) as FetchResult.Response
        core.ingest(request.requestId, response.status, response.body)

        val relaunched = AndroidCores.create(RuntimeEnvironment.getApplication()).open(embedded, empty = false)

        assertEquals(20L, relaunched.stats().productCount)
    }
}
