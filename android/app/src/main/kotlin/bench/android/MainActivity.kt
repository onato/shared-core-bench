package bench.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import bench.android.core.KmpSharedCore
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = KmpSharedCore.create(this, dbPath = "products.db")
        val fixtureRecords = intent.getIntExtra("fixtureRecords", 0)
        val client =
            if (fixtureRecords > 0) {
                fixtureClient(fixtureRecords, seed = intent.getLongExtra("fixtureSeed", 0))
            } else {
                OkHttpClient()
            }
        val fetcher = OkHttpFetcher(client)
        setContent {
            App(
                { productPages(core, fetcher::fetch, PAGE_SIZE, it) },
                core::detail,
                core::refresh,
                core::categories,
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
