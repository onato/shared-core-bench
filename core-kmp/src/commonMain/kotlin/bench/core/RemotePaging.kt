package bench.core

/** Offset paging over the remote API: one request is pending until a page for it is stored. */
internal class RemotePaging(
    private val baseUrl: String,
    private val pageSize: Int,
) {
    private var pendingId = 1L
    private var skip = 0L
    private var total = Long.MAX_VALUE

    fun next(): FetchRequest? =
        if (skip >= total) null else FetchRequest(requestId = pendingId, url = "$baseUrl?limit=$pageSize&skip=$skip")

    fun isPending(requestId: Long): Boolean = requestId == pendingId

    fun advance(reportedTotal: Long?) {
        pendingId++
        skip += pageSize
        reportedTotal?.let { total = it }
    }
}
