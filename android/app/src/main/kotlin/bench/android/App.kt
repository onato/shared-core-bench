package bench.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import bench.android.core.FetchRequest
import bench.android.core.ProductDetail
import bench.android.core.ProductSummary
import bench.android.core.SharedCore
import kotlinx.coroutines.flow.Flow

fun productPages(
    core: SharedCore,
    fetch: (FetchRequest) -> FetchResult,
    pageSize: Int,
): Flow<PagingData<ProductSummary>> = Pager(PagingConfig(pageSize)) { ProductPagingSource(core, fetch) }.flow

private fun CombinedLoadStates.firstError() = listOf(refresh, append).filterIsInstance<LoadState.Error>().firstOrNull()

// The root screen gains nothing from skipping, and its generated skip branches are unreachable in tests.
@NonSkippableComposable
@Composable
fun App(
    products: Flow<PagingData<ProductSummary>>,
    detail: (Long) -> ProductDetail?,
) {
    val items = products.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    when (val id = selected) {
        null -> {
            LazyColumn(state = listState) {
                item { Text("Products") }
                items(items.itemCount) { index ->
                    items[index]?.let { product ->
                        ListItem(
                            headlineContent = { Text(product.title) },
                            modifier = Modifier.clickable { selected = product.id },
                        )
                    }
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

        else -> {
            BackHandler { selected = null }
            remember(id) { detail(id) }?.let { ProductDetailScreen(it) } ?: Text("Product not found")
        }
    }
}
