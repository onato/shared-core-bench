import Foundation
import SharedCore

/// The app's `SharedCore` on the KMP core. The app module's types shadow the KMP ones of the same name,
/// so the KMP side is written `SharedCore::`.
struct KmpSharedCore: SharedCore {
    private let core: SharedCore::Core

    /// The KMP core embedded, on the SQLite database at `dbPath`.
    static func create(dbPath: String) -> KmpSharedCore {
        let config = CoreConfig(dbPath: dbPath, baseUrl: "https://dummyjson.com/products", pageSize: 20)
        return KmpSharedCore(core: Core.companion.create(config: config, drivers: NativeDrivers()))
    }

    func nextFetch() -> FetchRequest? {
        core.nextFetch().map { FetchRequest(requestId: $0.requestId, url: $0.url) }
    }

    func ingest(requestId: Int64, status: Int, body: Data) -> IngestResult {
        switch onEnum(of: core.ingest(requestId: requestId, status: Int32(status), body: kotlinBytes(body))) {
        case let .decoded(result): .ingested(accepted: Int(result.accepted), rejected: Int(result.rejected))
        case let .httpStatus(result): .httpStatus(Int(result.status))
        case .unknownRequest: .failed(.unknownRequest)
        case .invalidJson: .failed(.invalidJson)
        case .notAProductPage: .failed(.notAProductPage)
        case .storageFailed: .failed(.storageFailed)
        }
    }

    func page(cursor: Int64?, size: Int, query: Query) -> PageResult {
        let result = core.page(cursor: cursor.map(KotlinLong.init(value:)), size: Int32(size), query: kmpQuery(query))
        switch onEnum(of: result) {
        case let .page(page): return .page(items: page.items.map(summary), nextCursor: page.nextCursor?.int64Value)
        case .invalidSize: return .invalidSize
        case .storageFailed: return .storageFailed
        }
    }

    func detail(id: Int64) -> DetailResult {
        switch onEnum(of: core.detail(id: id)) {
        case let .found(result): .found(productDetail(result.product))
        case .notFound: .notFound
        case .storageFailed: .storageFailed
        }
    }

    func stats() -> StatsResult {
        switch onEnum(of: core.stats()) {
        case let .stats(result): .stats(CoreStats(
            productCount: result.stats.productCount, deletedCount: result.stats.deletedCount))
        case .storageFailed: .storageFailed
        }
    }

    func categories() -> CategoriesResult {
        switch onEnum(of: core.categories()) {
        case let .categories(result): .categories(result.names)
        case .storageFailed: .storageFailed
        }
    }

    func refresh() {
        core.refresh()
    }
}

private func kotlinBytes(_ data: Data) -> KotlinByteArray {
    let bytes = KotlinByteArray(size: Int32(data.count))
    for (index, byte) in data.enumerated() {
        bytes.set(index: Int32(index), value: Int8(bitPattern: byte))
    }
    return bytes
}

private func kmpQuery(_ query: Query) -> SharedCore::Query {
    SharedCore::Query(
        text: query.text,
        category: query.category,
        minPrice: query.minPrice.map(KotlinDouble.init(value:)),
        maxPrice: query.maxPrice.map(KotlinDouble.init(value:)),
        sort: kmpSort(query.sort))
}

private func kmpSort(_ sort: Sort) -> SharedCore::Sort {
    switch sort {
    case .id: .id
    case .priceAsc: .priceAsc
    case .priceDesc: .priceDesc
    case .ratingDesc: .ratingDesc
    }
}

private func summary(_ summary: SharedCore::ProductSummary) -> ProductSummary {
    ProductSummary(
        id: summary.id, title: summary.title, price: summary.price,
        discountedPrice: summary.discountedPrice, rating: summary.rating, thumbnail: summary.thumbnail)
}

private func productDetail(_ product: SharedCore::ProductDetail) -> ProductDetail {
    ProductDetail(
        id: product.id,
        title: product.title,
        description: product.description_,
        category: product.category,
        brand: product.brand,
        price: product.price,
        discountPercentage: product.discountPercentage,
        discountedPrice: product.discountedPrice,
        rating: product.rating,
        ratingBucket: ratingBucket(product.ratingBucket),
        stock: product.stock,
        stockStatus: stockStatus(product.stockStatus),
        thumbnail: product.thumbnail,
        tags: product.tags)
}

private func ratingBucket(_ bucket: SharedCore::RatingBucket) -> RatingBucket {
    switch bucket {
    case .excellent: .excellent
    case .good: .good
    case .fair: .fair
    case .poor: .poor
    }
}

private func stockStatus(_ status: SharedCore::StockStatus) -> StockStatus {
    switch status {
    case .inStock: .inStock
    case .lowStock: .lowStock
    case .outOfStock: .outOfStock
    }
}
