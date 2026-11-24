package bench.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import bench.android.core.ProductDetail
import bench.android.core.StockStatus

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
