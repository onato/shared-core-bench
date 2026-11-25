package bench.android.core

import bench.android.CoreChoice
import bench.android.CoreKind
import bench.android.FetchResult
import bench.android.OkHttpFetcher
import bench.android.Storage
import bench.android.fixtureClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AndroidCoresTest {
    private val cores = AndroidCores(File.createTempFile("products", ".db").path, JvmDrivers)
    private val fetcher = OkHttpFetcher(fixtureClient(records = 20, seed = 7))

    private fun ingestFirstPage(core: SharedCore) {
        val request = checkNotNull(core.nextFetch())
        val response = fetcher.fetch(request) as FetchResult.Response
        core.ingest(request.requestId, response.status, response.body)
    }

    private fun statsAfterReopening(
        storage: Storage,
        empty: Boolean,
    ): StatsResult {
        val choice = CoreChoice(CoreKind.Kmp, storage)
        ingestFirstPage(cores.open(choice, empty = true))
        return cores.open(choice, empty).stats()
    }

    @Test
    fun everyStorageVariantKeepsItsProductsWhenReopened() {
        assertEquals(
            Storage.entries.associateWith { StatsResult.Stats(CoreStats(productCount = 20, deletedCount = 0)) },
            Storage.entries.associateWith { statsAfterReopening(it, empty = false) },
        )
    }

    @Test
    fun everyStorageVariantStartsWithNoProductsWhenReopenedEmpty() {
        assertEquals(
            Storage.entries.associateWith { StatsResult.Stats(CoreStats(productCount = 0, deletedCount = 0)) },
            Storage.entries.associateWith { statsAfterReopening(it, empty = true) },
        )
    }
}
