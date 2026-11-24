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
        val fetcher = OkHttpFetcher(OkHttpClient())
        setContent { App({ productPages(core, fetcher::fetch, PAGE_SIZE, it) }, core::detail) }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
