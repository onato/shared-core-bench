package bench.core

/**
 * Entry points for the boundary micro-benchmarks (SPEC.md 1–5) and nothing else: each does as little as its
 * shape allows, so a measurement is the cost of crossing into the core and back.
 */
object Boundary {
    fun noOp() = Unit

    fun echo(text: String): String = text

    fun bytesIn(bytes: ByteArray): Int = bytes.size

    fun records(count: Int): List<ProductSummary> =
        (1L..count).map { id ->
            ProductSummary(id, "Product $id", PRICE, DISCOUNTED_PRICE, RATING, "https://cdn.dummyjson.com/$id.png")
        }

    fun callback(
        times: Int,
        onCall: (Int) -> Unit,
    ) = repeat(times, onCall)

    private const val PRICE = 10.0
    private const val DISCOUNTED_PRICE = 9.0
    private const val RATING = 4.5
}
