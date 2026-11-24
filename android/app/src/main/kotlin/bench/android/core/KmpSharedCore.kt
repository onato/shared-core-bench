package bench.android.core

import android.content.Context
import bench.core.AndroidDrivers
import bench.core.Core
import bench.core.CoreConfig
import bench.core.IngestResult as CoreIngestResult
import bench.core.PageResult as CorePageResult
import bench.core.ProductSummary as CoreProductSummary
import bench.core.RatingBucket as CoreRatingBucket
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
    ): PageResult =
        when (val result = core.page(cursor, size)) {
            is CorePageResult.Page -> PageResult.Page(result.items.map(::product), result.nextCursor)
            CorePageResult.InvalidSize -> PageResult.InvalidSize
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

    override fun stats(): CoreStats = CoreStats(core.stats().productCount)

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

    private fun product(product: CoreProductSummary) = ProductSummary(product.id, product.title, product.price)

    companion object {
        fun create(
            context: Context,
            dbPath: String,
        ) = KmpSharedCore(Core.create(CoreConfig(dbPath), AndroidDrivers(context)))
    }
}
