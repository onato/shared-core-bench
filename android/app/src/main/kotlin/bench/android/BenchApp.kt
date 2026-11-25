package bench.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import bench.android.core.FetchRequest

// The root screen gains nothing from skipping, and its generated skip branches are unreachable in tests.
@NonSkippableComposable
@Composable
fun BenchApp(
    session: CoreSession,
    fetch: (FetchRequest) -> FetchResult,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        BackHandler { showSettings = false }
        Column {
            Text("Core: ${session.choice.core.label}")
            Text("Storage: ${session.choice.storage.label}")
            session.chooseStorage.forEach { (storage, choose) ->
                Button(onClick = choose) { Text(storage.label) }
            }
        }
    } else {
        Column {
            Button(onClick = { showSettings = true }) { Text("Settings") }
            key(session.core) {
                val core = session.core
                App({ productPages(core, fetch, PAGE_SIZE, it) }, core::detail, core::refresh, core::categories)
            }
        }
    }
}

private const val PAGE_SIZE = 20
