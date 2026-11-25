package bench.core

/**
 * Storage the app owns, over the embedded schema and SQL. The core keeps every decision and only
 * asks the port to run statements. A write fails by returning a [StorageError], never by throwing;
 * [transaction] rolls back everything the block wrote and returns the error when the block returns one
 * or the commit fails.
 */
interface StoragePort {
    fun transaction(block: () -> StorageResult<Unit>): StorageResult<Unit>

    /** Writes [items] in one call, each marked as seen by the walk of [generation]. */
    fun upsert(
        items: List<ProductDetail>,
        generation: Long,
    ): StorageResult<Unit>

    /** Writes one product, marked as seen by the walk of [generation]. */
    fun upsertOne(
        item: ProductDetail,
        generation: Long,
    ): StorageResult<Unit>

    fun markMissingDeleted(generation: Long): StorageResult<Unit>

    /** Up to [size] products after [cursor] under [query]'s filters and sort. */
    fun query(
        query: Query,
        cursor: Long?,
        size: Int,
    ): List<ProductSummary>

    fun get(id: Long): ProductDetail?

    fun categories(): List<String>

    fun stats(): CoreStats
}
