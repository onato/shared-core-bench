package bench.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import bench.android.core.CategoriesResult
import bench.android.core.DetailResult
import bench.android.core.FetchRequest
import bench.android.core.ProductDetail
import bench.android.core.ProductSummary
import bench.android.core.Query
import bench.android.core.SharedCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

fun productPages(
    core: SharedCore,
    fetch: (FetchRequest) -> FetchResult,
    pageSize: Int,
    query: Query,
): Flow<PagingData<ProductSummary>> = Pager(PagingConfig(pageSize)) { ProductPagingSource(core, fetch, query) }.flow

private fun CombinedLoadStates.firstError() = listOf(refresh, append).filterIsInstance<LoadState.Error>().firstOrNull()

internal fun dollars(amount: Double) = String.format(Locale.US, "$%.2f", amount)

private fun struckThrough(text: String) =
    buildAnnotatedString { withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text) } }

@Composable
private fun ProductCell(
    product: ProductSummary,
    modifier: Modifier,
) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text(product.title) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dollars(product.discountedPrice))
                if (product.discountedPrice != product.price) Text(struckThrough(dollars(product.price)))
            }
        },
        trailingContent = { Text(String.format(Locale.US, "%.1f", product.rating)) },
    )
}

// The root screen gains nothing from skipping, and its generated skip branches are unreachable in tests.
@NonSkippableComposable
@Composable
fun App(
    products: (Query) -> Flow<PagingData<ProductSummary>>,
    detail: (Long) -> DetailResult,
    refresh: () -> Unit,
    categories: () -> CategoriesResult,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val filters = rememberFilters()
    val query = filters.query(text)
    val items = remember(query) { products(query) }.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    when (val id = selected) {
        null -> {
            Column {
                TextField(value = text, onValueChange = { text = it }, label = { Text("Search") }, singleLine = true)
                FilterControls(filters, categories)
                PullToRefreshBox(
                    isRefreshing = items.loadState.refresh is LoadState.Loading && items.itemCount > 0,
                    onRefresh = {
                        scope.launch {
                            withContext(Dispatchers.IO) { refresh() }
                            items.refresh()
                        }
                    },
                ) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        item { Text("Products") }
                        items(items.itemCount) { index ->
                            items[index]?.let { product ->
                                ProductCell(product, Modifier.clickable { selected = product.id })
                            }
                        }
                        if (items.itemCount == 0 && items.loadState.append.endOfPaginationReached) {
                            item { Text(if (text.isEmpty()) "No products" else "No products match") }
                        }
                        val loadingFirstPage = items.loadState.refresh is LoadState.Loading && items.itemCount == 0
                        if (loadingFirstPage || items.loadState.append is LoadState.Loading) {
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
            }
        }

        else -> {
            BackHandler { selected = null }
            val found = remember(id) { detail(id) } as? DetailResult.Found
            found?.let { ProductDetailScreen(it.product) } ?: Text("Product not found")
        }
    }
}
