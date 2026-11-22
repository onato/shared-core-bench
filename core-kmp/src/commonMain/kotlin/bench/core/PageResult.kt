package bench.core

sealed interface PageResult {
    data class Page(
        val items: List<Product>,
        val nextCursor: Long?,
    ) : PageResult

    data object InvalidSize : PageResult
}
