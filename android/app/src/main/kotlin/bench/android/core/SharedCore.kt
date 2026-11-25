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

    fun detail(id: Long): DetailResult

    fun stats(): StatsResult

    /** The categories of the products a page can show, sorted. */
    fun categories(): CategoriesResult

    /** Starts the remote walk again from the first page. */
    fun refresh()
}

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

/** The order a page lists products in; ties order by ascending id. The price sorts use the discounted price. */
enum class Sort { Id, PriceAsc, PriceDesc, RatingDesc }

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

sealed interface DetailResult {
    data class Found(
        val product: ProductDetail,
    ) : DetailResult

    /** No stored product has the id, or it is soft-deleted. */
    data object NotFound : DetailResult

    data object StorageFailed : DetailResult
}

sealed interface StatsResult {
    data class Stats(
        val stats: CoreStats,
    ) : StatsResult

    data object StorageFailed : StatsResult
}

sealed interface CategoriesResult {
    data class Categories(
        val names: List<String>,
    ) : CategoriesResult

    data object StorageFailed : CategoriesResult
}

enum class RatingBucket { Excellent, Good, Fair, Poor }

enum class StockStatus { InStock, LowStock, OutOfStock }

/** [productCount] counts every stored product, soft-deleted ones included. */
data class CoreStats(
    val productCount: Long,
    val deletedCount: Long,
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

    data object StorageFailed : PageResult
}
