package bench.android.core

import android.content.Context
import bench.android.CoreChoice
import bench.android.CoreFactory
import bench.android.Storage
import bench.core.AndroidDrivers
import bench.core.Core
import bench.core.CoreConfig
import bench.core.DriverFactory
import bench.core.PortCalls
import java.io.File
import bench.core.DetailResult as CoreDetailResult
import bench.core.IngestResult as CoreIngestResult
import bench.core.PageResult as CorePageResult
import bench.core.ProductDetail as CoreProductDetail
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
            CorePageResult.StorageFailed -> PageResult.StorageFailed
        }

    private fun coreQuery(query: Query) = with(query) { CoreQuery(text, category, minPrice, maxPrice, coreSort(sort)) }

    private fun coreSort(sort: Sort) =
        when (sort) {
            Sort.Id -> CoreSort.Id
            Sort.PriceAsc -> CoreSort.PriceAsc
            Sort.PriceDesc -> CoreSort.PriceDesc
            Sort.RatingDesc -> CoreSort.RatingDesc
        }

    override fun detail(id: Long): DetailResult =
        when (val result = core.detail(id)) {
            is CoreDetailResult.Found -> DetailResult.Found(detailOf(result.product))
            CoreDetailResult.NotFound -> DetailResult.NotFound
            CoreDetailResult.StorageFailed -> DetailResult.StorageFailed
        }

    private fun detailOf(product: CoreProductDetail) =
        with(product) {
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

/** Opens the KMP core on the database at [path], embedded on [drivers] or on the app's [SqliteStoragePort]. */
class AndroidCores(
    private val path: String,
    private val drivers: DriverFactory,
) : CoreFactory {
    override fun open(
        choice: CoreChoice,
        empty: Boolean,
    ): SharedCore {
        if (empty) SQLITE_FILES.forEach { File(path + it).delete() }
        val config = CoreConfig(path)
        return KmpSharedCore(
            when (choice.storage) {
                Storage.Embedded -> Core.create(config, drivers)
                Storage.Batched -> Core.create(config, SqliteStoragePort.open(path))
                Storage.Chatty -> Core.create(config, SqliteStoragePort.open(path), PortCalls.CHATTY)
            },
        )
    }

    companion object {
        fun create(context: Context) =
            AndroidCores(context.getDatabasePath("products.db").path, AndroidDrivers(context))
    }
}

// The database and the journal files SQLite keeps beside it.
private val SQLITE_FILES = listOf("", "-journal", "-wal", "-shm")
