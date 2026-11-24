package bench.android.core

/** The only view of a core the app has; each core gets an adapter behind it. */
interface SharedCore {
    fun nextFetch(): FetchRequest?

    fun ingest(
        requestId: Long,
        status: Int,
        body: ByteArray,
    ): IngestResult

    fun page(
        cursor: Long?,
        size: Int,
        query: Query = Query(),
    ): PageResult

    fun detail(id: Long): ProductDetail?

    fun stats(): CoreStats
}

/** Which products a page holds; an empty [text] matches every product. */
data class Query(
    val text: String = "",
)

data class FetchRequest(
    val requestId: Long,
    val url: String,
)

data class ProductSummary(
    val id: Long,
    val title: String,
    val price: Double,
    val discountedPrice: Double,
    val rating: Double,
    val thumbnail: String,
)

data class ProductDetail(
    val id: Long,
    val title: String,
    val description: String,
    val category: String,
    val brand: String?,
    val price: Double,
    val discountPercentage: Double,
    val discountedPrice: Double,
    val rating: Double,
    val ratingBucket: RatingBucket,
    val stock: Long,
    val stockStatus: StockStatus,
    val thumbnail: String,
    val tags: List<String>,
)

enum class RatingBucket { Excellent, Good, Fair, Poor }

enum class StockStatus { InStock, LowStock, OutOfStock }

data class CoreStats(
    val productCount: Long,
)

sealed interface IngestResult {
    data class Ingested(
        val accepted: Int,
        val rejected: Int,
    ) : IngestResult

    data class HttpStatus(
        val status: Int,
    ) : IngestResult

    data class Failed(
        val reason: IngestFailure,
    ) : IngestResult
}

enum class IngestFailure { UnknownRequest, InvalidJson, NotAProductPage, StorageFailed }

sealed interface PageResult {
    data class Page(
        val items: List<ProductSummary>,
        val nextCursor: Long?,
    ) : PageResult

    data object InvalidSize : PageResult
}
