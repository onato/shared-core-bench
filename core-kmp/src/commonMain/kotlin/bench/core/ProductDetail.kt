package bench.core

import kotlin.math.roundToLong

data class ProductDetail(
    val id: Long,
    val title: String,
    val description: String,
    val category: String,
    val brand: String?,
    val price: Double,
    val discountPercentage: Double,
    val rating: Double,
    val stock: Long,
    val thumbnail: String,
    val tags: List<String>,
) {
    val discountedPrice: Double get() = discountedPrice(price, discountPercentage)

    val ratingBucket: RatingBucket get() = RatingBucket.of(rating)

    val stockStatus: StockStatus get() = StockStatus.of(stock)
}

private const val UNITS = 10_000L
private const val PERCENT = 100
private const val CENTS = 100.0

/** [price] less [discountPercentage], rounded half up to cents. */
internal fun discountedPrice(
    price: Double,
    discountPercentage: Double,
): Double {
    // Whole ten-thousandths, so a binary fraction can't move a half cent across the rounding line.
    val priceUnits = (price * UNITS).roundToLong()
    val remainingUnits = ((PERCENT - discountPercentage) * UNITS).roundToLong()
    val unitsPerCent = UNITS * UNITS
    val cents = (priceUnits * remainingUnits + unitsPerCent / 2) / unitsPerCent
    return cents / CENTS
}
