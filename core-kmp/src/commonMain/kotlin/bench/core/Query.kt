package bench.core

/**
 * Which products a page holds: those that every filter keeps. An empty [text] or a null filter keeps every
 * product; the price bounds are inclusive and apply to the discounted price.
 */
data class Query(
    val text: String = "",
    val category: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val sort: Sort = Sort.Id,
)

/** The order a page lists products in; ties always fall back to ascending id. */
enum class Sort(
    internal val key: String,
) {
    Id("id"),
    PriceAsc("price-asc"),
}
