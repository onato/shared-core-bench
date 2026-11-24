package bench.android.core

import android.content.Context
import bench.core.AndroidDrivers
import bench.core.Core
import bench.core.CoreConfig
import bench.core.IngestResult as CoreIngestResult
import bench.core.PageResult as CorePageResult
import bench.core.ProductSummary as CoreProductSummary
import bench.core.Query as CoreQuery
import bench.core.RatingBucket as CoreRatingBucket
import bench.core.Sort as CoreSort
import bench.core.StockStatus as CoreStockStatus

class KmpSharedCore(
    private val core: Core,
) : SharedCore {
    override fun nextFetch(): FetchRequest? = core.nextFetch()?.let { FetchRequest(it.requestId, it.url) }

    override fun ingest(
        requestId: Long,
        status: Int,
        body: ByteArray,
    ): IngestResult =
        when (val result = core.ingest(requestId, status, body)) {
            is CoreIngestResult.Decoded -> IngestResult.Ingested(result.accepted, result.rejected)
            is CoreIngestResult.HttpStatus -> IngestResult.HttpStatus(result.status)
            CoreIngestResult.UnknownRequest -> IngestResult.Failed(IngestFailure.UnknownRequest)
            CoreIngestResult.InvalidJson -> IngestResult.Failed(IngestFailure.InvalidJson)
            CoreIngestResult.NotAProductPage -> IngestResult.Failed(IngestFailure.NotAProductPage)
            CoreIngestResult.StorageFailed -> IngestResult.Failed(IngestFailure.StorageFailed)
        }

    override fun page(
        cursor: Long?,
        size: Int,
        query: Query,
    ): PageResult =
        when (val result = core.page(cursor, size, coreQuery(query))) {
            is CorePageResult.Page -> PageResult.Page(result.items.map(::summary), result.nextCursor)
            CorePageResult.InvalidSize -> PageResult.InvalidSize
        }

    private fun coreQuery(query: Query) = with(query) { CoreQuery(text, category, minPrice, maxPrice, coreSort(sort)) }

    private fun coreSort(sort: Sort) =
        when (sort) {
            Sort.Id -> CoreSort.Id
            Sort.PriceAsc -> CoreSort.PriceAsc
            Sort.PriceDesc -> CoreSort.PriceDesc
            Sort.RatingDesc -> CoreSort.RatingDesc
        }

    override fun detail(id: Long): ProductDetail? =
        core.detail(id)?.run {
            ProductDetail(
                id,
                title,
                description,
                category,
                brand,
                price,
                discountPercentage,
                discountedPrice,
                rating,
                ratingBucket(ratingBucket),
                stock,
                stockStatus(stockStatus),
                thumbnail,
                tags,
            )
        }

    override fun stats(): CoreStats = with(core.stats()) { CoreStats(productCount, deletedCount) }

    override fun refresh() = core.refresh()

    override fun categories() = core.categories()

    companion object {
        fun create(
            context: Context,
            dbPath: String,
        ) = KmpSharedCore(Core.create(CoreConfig(dbPath), AndroidDrivers(context)))
    }
}

private fun ratingBucket(bucket: CoreRatingBucket) =
    when (bucket) {
        CoreRatingBucket.Excellent -> RatingBucket.Excellent
        CoreRatingBucket.Good -> RatingBucket.Good
        CoreRatingBucket.Fair -> RatingBucket.Fair
        CoreRatingBucket.Poor -> RatingBucket.Poor
    }

private fun stockStatus(status: CoreStockStatus) =
    when (status) {
        CoreStockStatus.InStock -> StockStatus.InStock
        CoreStockStatus.LowStock -> StockStatus.LowStock
        CoreStockStatus.OutOfStock -> StockStatus.OutOfStock
    }

private fun summary(summary: CoreProductSummary) =
    with(summary) { ProductSummary(id, title, price, discountedPrice, rating, thumbnail) }
