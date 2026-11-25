package bench.core

sealed interface PageResult {
    data class Page(
        val items: List<ProductSummary>,
        val nextCursor: Long?,
    ) : PageResult

    data object InvalidSize : PageResult

    data object StorageFailed : PageResult
}
