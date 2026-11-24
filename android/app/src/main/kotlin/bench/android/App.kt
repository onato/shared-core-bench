package bench.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
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
import bench.android.core.FetchRequest
import bench.android.core.ProductSummary
import bench.android.core.SharedCore
import kotlinx.coroutines.flow.Flow
import java.util.Locale

fun productPages(
    core: SharedCore,
    fetch: (FetchRequest) -> FetchResult,
    pageSize: Int,
): Flow<PagingData<ProductSummary>> = Pager(PagingConfig(pageSize)) { ProductPagingSource(core, fetch) }.flow

private fun CombinedLoadStates.firstError() = listOf(refresh, append).filterIsInstance<LoadState.Error>().firstOrNull()

private fun dollars(amount: Double) = String.format(Locale.US, "$%.2f", amount)

private fun struckThrough(text: String) =
    buildAnnotatedString { withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text) } }

@Composable
private fun ProductCell(product: ProductSummary) {
    ListItem(
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
fun App(products: Flow<PagingData<ProductSummary>>) {
    val items = products.collectAsLazyPagingItems()
    LazyColumn {
        item { Text("Products") }
        items(items.itemCount) { index ->
            items[index]?.let { ProductCell(it) }
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
