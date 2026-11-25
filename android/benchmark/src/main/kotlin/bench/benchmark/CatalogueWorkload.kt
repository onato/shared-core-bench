package bench.benchmark

import bench.core.PageResult
import bench.core.Query

/** Benchmarks 8 and 9: a database already holding [rows] generated products, read [pageSize] at a time. */
class CatalogueWorkload(
    cores: FreshCores,
    rows: Int,
    seed: Long,
    private val pageSize: Int,
) : AutoCloseable {
    private val ingest = IngestWorkload(cores, rows, seed)
    private val core = ingest.freshCore().also(ingest::ingestInto)

    /** Follows the cursor from the first page to the last; the ids in the order the pages held them. */
    fun pageThrough(query: Query): List<Long> {
        val ids = mutableListOf<Long>()
        var cursor: Long? = null
        do {
            val page = page(cursor, query)
            page.items.mapTo(ids) { it.id }
            cursor = page.nextCursor
        } while (cursor != null)
        return ids
    }

    /** The ids on the first page [query] keeps. */
    fun firstPage(query: Query): List<Long> = page(cursor = null, query).items.map { it.id }

    private fun page(
        cursor: Long?,
        query: Query,
    ) = core.page(cursor, pageSize, query) as PageResult.Page

    override fun close() = ingest.close()
}
