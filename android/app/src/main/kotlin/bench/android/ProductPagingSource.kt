package bench.android

import androidx.paging.PagingSource
import androidx.paging.PagingState
import bench.android.core.FetchRequest
import bench.android.core.IngestResult
import bench.android.core.PageResult
import bench.android.core.ProductSummary
import bench.android.core.Query
import bench.android.core.SharedCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pages products out of the core, fetching and ingesting remote pages when local ones run out. */
class ProductPagingSource(
    private val core: SharedCore,
    private val fetch: (FetchRequest) -> FetchResult,
    private val query: Query,
) : PagingSource<Long, ProductSummary>() {
    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, ProductSummary> =
        withContext(Dispatchers.IO) { load(params.key, params.loadSize) }

    private tailrec fun load(
        cursor: Long?,
        size: Int,
    ): LoadResult<Long, ProductSummary> {
        // Paging rejects a non-positive page size in PagingConfig, so InvalidSize cannot come back.
        val page = core.page(cursor, size, query) as PageResult.Page
        val request = if (page.nextCursor == null) core.nextFetch() else null
        if (request == null) return page.toLoadResult()
        val failure = fetchAndIngest(request)
        return if (failure != null) LoadResult.Error(failure) else load(cursor, size)
    }

    private fun fetchAndIngest(request: FetchRequest): RemoteLoadException? =
        when (val response = fetch(request)) {
            is FetchResult.NetworkError -> {
                RemoteLoadException("Network error: ${response.reason}")
            }

            is FetchResult.Response -> {
                core
                    .ingest(request.requestId, response.status, response.body)
                    .takeUnless { it is IngestResult.Ingested }
                    ?.let { RemoteLoadException("Remote load failed: $it") }
            }
        }

    override fun getRefreshKey(state: PagingState<Long, ProductSummary>): Long? = null
}

class RemoteLoadException(
    message: String,
) : Exception(message)

private fun PageResult.Page.toLoadResult() = PagingSource.LoadResult.Page(items, prevKey = null, nextKey = nextCursor)
