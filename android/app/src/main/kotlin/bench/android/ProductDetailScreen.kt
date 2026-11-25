package bench.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import bench.android.core.DetailResult
import bench.android.core.ProductDetail
import bench.android.core.StockStatus

// Like App: a screen gains nothing from skipping, and its generated skip branches are unreachable in tests.
@NonSkippableComposable
@Composable
fun LoadedProductDetail(
    id: Long,
    detail: (Long) -> DetailResult,
) {
    var reads by remember(id) { mutableIntStateOf(0) }
    when (val result = remember(id, reads) { detail(id) }) {
        is DetailResult.Found -> {
            ProductDetailScreen(result.product)
        }

        DetailResult.NotFound -> {
            Text("Product not found")
        }

        // DetailResult.StorageFailed. Listing it leaves an unreachable NoWhenBranchMatchedException
        // throw that Kover counts inside a composable.
        else -> {
            Column {
                Text("Couldn't load this product")
                Button(onClick = { reads++ }) { Text("Retry") }
            }
        }
    }
}

// Like App: a screen gains nothing from skipping, and its generated skip branches are unreachable in tests.
@NonSkippableComposable
@Composable
fun ProductDetailScreen(product: ProductDetail) {
    Column {
        Text(product.title)
        Text(product.description)
        product.brand?.let { Text("Brand: $it") }
        Text("Category: ${product.category}")
        Text("${dollars(product.discountedPrice)}, was ${dollars(product.price)}")
        Text("Rating: ${product.ratingBucket}")
        Text(stockLabel(product.stockStatus))
        Text("Tags: ${product.tags.joinToString()}")
    }
}

private fun stockLabel(status: StockStatus) =
    when (status) {
        StockStatus.InStock -> "In stock"
        StockStatus.LowStock -> "Low stock"
        StockStatus.OutOfStock -> "Out of stock"
    }
