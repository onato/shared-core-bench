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
import androidx.paging.PagingData
import bench.android.core.FetchRequest
import bench.android.core.ProductSummary
import bench.android.core.Query
import bench.android.core.SharedCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.CoroutineContext

// The root screen gains nothing from skipping, and its generated skip branches are unreachable in tests.
@NonSkippableComposable
@Composable
fun BenchApp(
    session: CoreSession,
    fetch: (FetchRequest) -> FetchResult,
    background: CoroutineContext = Dispatchers.IO,
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
                App(
                    products(core, fetch, background),
                    core::detail,
                    core::refresh,
                    core::categories,
                    background,
                )
            }
        }
    }
}

// Built outside composition: an inline lambda gets Compose's memoization branches, unreachable in tests.
private fun products(
    core: SharedCore,
    fetch: (FetchRequest) -> FetchResult,
    background: CoroutineContext,
): (Query) -> Flow<PagingData<ProductSummary>> = { productPages(core, fetch, PAGE_SIZE, it, background) }

private const val PAGE_SIZE = 20
