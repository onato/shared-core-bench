package bench.core

sealed interface DetailResult {
    data class Found(
        val product: ProductDetail,
    ) : DetailResult

    /** No stored product has the id, or it is soft-deleted. */
    data object NotFound : DetailResult

    data object StorageFailed : DetailResult
}
