package bench.android

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import bench.android.core.FetchRequest
import bench.android.core.Product
import bench.android.core.SharedCore
import kotlinx.coroutines.flow.Flow

fun productPages(
    core: SharedCore,
    fetch: (FetchRequest) -> FetchResult,
    pageSize: Int,
): Flow<PagingData<Product>> = Pager(PagingConfig(pageSize)) { ProductPagingSource(core, fetch) }.flow

private fun CombinedLoadStates.firstError() = listOf(refresh, append).filterIsInstance<LoadState.Error>().firstOrNull()

// The root screen gains nothing from skipping, and its generated skip branches are unreachable in tests.
@NonSkippableComposable
@Composable
fun App(products: Flow<PagingData<Product>>) {
    val items = products.collectAsLazyPagingItems()
    LazyColumn {
        item { Text("Products") }
        items(items.itemCount) { index ->
            items[index]?.let { ListItem(headlineContent = { Text(it.title) }) }
        }
        if (items.itemCount == 0 && items.loadState.append.endOfPaginationReached) {
            item { Text("No products") }
        }
        if (items.loadState.refresh is LoadState.Loading || items.loadState.append is LoadState.Loading) {
            item { CircularProgressIndicator() }
        }
        items.loadState.firstError()?.let { failure ->
            item {
                Text(failure.error.message.orEmpty())
                Button(onClick = items::retry) { Text("Retry") }
            }
        }
    }
}
