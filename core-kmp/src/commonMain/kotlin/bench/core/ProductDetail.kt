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
    /** [price] less [discountPercentage], rounded half up to cents. */
    val discountedPrice: Double
        get() {
            // Whole ten-thousandths, so a binary fraction can't move a half cent across the rounding line.
            val priceUnits = (price * UNITS).roundToLong()
            val remainingUnits = ((PERCENT - discountPercentage) * UNITS).roundToLong()
            val unitsPerCent = UNITS * UNITS
            val cents = (priceUnits * remainingUnits + unitsPerCent / 2) / unitsPerCent
            return cents / CENTS
        }

    val ratingBucket: RatingBucket get() = RatingBucket.of(rating)

    private companion object {
        const val UNITS = 10_000L
        const val PERCENT = 100
        const val CENTS = 100.0
    }
}
