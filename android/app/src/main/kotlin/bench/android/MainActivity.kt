package bench.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import bench.android.core.AndroidCores
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cores = AndroidCores.create(this)
        val session = CoreSession(Settings(this), cores)
        val fixtureRecords = intent.getIntExtra("fixtureRecords", 0)
        val client =
            if (fixtureRecords > 0) {
                fixtureClient(fixtureRecords, seed = intent.getLongExtra("fixtureSeed", 0))
            } else {
                OkHttpClient()
            }
        val fetcher = OkHttpFetcher(client)
        setContent { BenchApp(session, fetcher::fetch) }
    }
}
