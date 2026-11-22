package bench.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import bench.android.core.KmpSharedCore
import bench.core.AndroidDrivers
import bench.core.Core
import bench.core.CoreConfig
import okhttp3.OkHttpClient
import bench.core.FetchRequest as CoreFetchRequest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = KmpSharedCore(Core.create(CoreConfig(dbPath = "products.db"), AndroidDrivers(this)))
        val fetcher = OkHttpFetcher(OkHttpClient())
        val products =
            productPages(core, { fetcher.fetch(CoreFetchRequest(it.requestId, it.url)) }, pageSize = PAGE_SIZE)
        setContent { App(products) }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
