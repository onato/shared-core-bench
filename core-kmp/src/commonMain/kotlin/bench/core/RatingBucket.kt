package bench.core

enum class RatingBucket(
    val from: Double,
) {
    Excellent(from = 4.5),
    Good(from = 4.0),
    Fair(from = 3.0),
    Poor(from = Double.NEGATIVE_INFINITY),
    ;

    companion object {
        fun of(rating: Double): RatingBucket = entries.first { rating >= it.from }
    }
}
