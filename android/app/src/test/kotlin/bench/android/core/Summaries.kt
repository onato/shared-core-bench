package bench.android.core

/** A summary for tests that only look at a product's id, title and price. */
fun summary(
    id: Long,
    title: String,
    price: Double,
) = ProductSummary(id, title, price, discountedPrice = price, rating = 0.0, thumbnail = "")
