import Foundation

/// The only view of a core the app has; each core gets an adapter behind it.
protocol SharedCore {
    func nextFetch() -> FetchRequest?
    func ingest(requestId: Int64, status: Int, body: Data) -> IngestResult
    func page(cursor: Int64?, size: Int, query: Query) -> PageResult
    func detail(id: Int64) -> DetailResult
    func stats() -> StatsResult
    /// The categories of the products a page can show, sorted.
    func categories() -> CategoriesResult
    /// Starts the remote walk again from the first page.
    func refresh()
}

/// Which products a page holds: those that every filter keeps. An empty `text` or a nil filter keeps every
/// product; the price bounds are inclusive and apply to the discounted price.
struct Query: Equatable {
    var text = ""
    var category: String?
    var minPrice: Double?
    var maxPrice: Double?
    var sort = Sort.id
}

/// The order a page lists products in; ties order by ascending id. The price sorts use the discounted price.
enum Sort { case id, priceAsc, priceDesc, ratingDesc }

struct FetchRequest: Equatable {
    let requestId: Int64
    let url: String
}

struct ProductSummary: Equatable {
    let id: Int64
    let title: String
    let price: Double
    let discountedPrice: Double
    let rating: Double
    let thumbnail: String
}

struct ProductDetail: Equatable {
    let id: Int64
    let title: String
    let description: String
    let category: String
    let brand: String?
    let price: Double
    let discountPercentage: Double
    let discountedPrice: Double
    let rating: Double
    let ratingBucket: RatingBucket
    let stock: Int64
    let stockStatus: StockStatus
    let thumbnail: String
    let tags: [String]
}

enum DetailResult: Equatable {
    case found(ProductDetail)
    /// No stored product has the id, or it is soft-deleted.
    case notFound
    case storageFailed
}

enum StatsResult: Equatable {
    case stats(CoreStats)
    case storageFailed
}

enum CategoriesResult: Equatable {
    case categories([String])
    case storageFailed
}

enum RatingBucket { case excellent, good, fair, poor }

enum StockStatus { case inStock, lowStock, outOfStock }

/// `productCount` counts every stored product, soft-deleted ones included.
struct CoreStats: Equatable {
    let productCount: Int64
    let deletedCount: Int64
}

enum IngestResult: Equatable {
    case ingested(accepted: Int, rejected: Int)
    case httpStatus(Int)
    case failed(IngestFailure)
}

enum IngestFailure { case unknownRequest, invalidJson, notAProductPage, storageFailed }

enum PageResult: Equatable {
    case page(items: [ProductSummary], nextCursor: Int64?)
    case invalidSize
    case storageFailed
}
