import Foundation
@testable import SharedCoreBench

/// Speaks the contract cases' JSON to any `SharedCore`.
struct SharedCoreContract: ContractCore {
    let core: SharedCore

    func ingest(_ args: [String: JSON]) -> JSON {
        let body = Data(string(args["body"]).utf8)
        switch core.ingest(requestId: Int64(int(args["requestId"])), status: int(args["status"]), body: body) {
        case let .ingested(accepted, rejected): return .object(["accepted": .int(accepted), "rejected": .int(rejected)])
        case let .httpStatus(status): return .object(["error": .string("http-status"), "status": .int(status)])
        case let .failed(reason): return error(Self.failureCodes[reason] ?? "")
        }
    }

    func page(_ args: [String: JSON]) -> JSON {
        let cursor = args["cursor"].flatMap { if case let .int(id) = $0 { Int64(id) } else { nil } }
        switch core.page(cursor: cursor, size: int(args["size"]), query: query(args["query"])) {
        case let .page(items, nextCursor):
            return .object([
                "items": .array(items.map(summary)),
                "nextCursor": nextCursor.map { .int(Int($0)) } ?? .null
            ])
        case .invalidSize: return error("invalid-page-size")
        case .storageFailed: return error("storage-failed")
        }
    }

    func nextFetch(_ args: [String: JSON]) -> JSON {
        guard let request = core.nextFetch() else { return .null }
        return .object(["requestId": .int(Int(request.requestId)), "url": .string(request.url)])
    }

    func detail(_ args: [String: JSON]) -> JSON {
        switch core.detail(id: Int64(int(args["id"]))) {
        case let .found(product): detail(product)
        case .notFound: .null
        case .storageFailed: error("storage-failed")
        }
    }

    func stats(_ args: [String: JSON]) -> JSON {
        switch core.stats() {
        case let .stats(stats):
            .object(["productCount": .int(Int(stats.productCount)), "deletedCount": .int(Int(stats.deletedCount))])
        case .storageFailed: error("storage-failed")
        }
    }

    func refresh(_ args: [String: JSON]) -> JSON {
        core.refresh()
        return .null
    }

    func categories(_ args: [String: JSON]) -> JSON {
        switch core.categories() {
        case let .categories(names): .array(names.map(JSON.string))
        case .storageFailed: error("storage-failed")
        }
    }

    private func query(_ json: JSON?) -> Query {
        Query(
            text: string(json?["text"]),
            category: json?["category"].flatMap { if case let .string(name) = $0 { name } else { nil } },
            minPrice: json?["minPrice"].flatMap(double),
            maxPrice: json?["maxPrice"].flatMap(double),
            sort: json?["sort"].flatMap { if case let .string(key) = $0 { Self.sorts[key] } else { nil } } ?? .id)
    }

    private func summary(_ summary: ProductSummary) -> JSON {
        .object([
            "id": .int(Int(summary.id)),
            "title": .string(summary.title),
            "price": .double(summary.price),
            "discountedPrice": .double(summary.discountedPrice),
            "rating": .double(summary.rating),
            "thumbnail": .string(summary.thumbnail)
        ])
    }

    private func detail(_ product: ProductDetail) -> JSON {
        .object([
            "id": .int(Int(product.id)),
            "title": .string(product.title),
            "description": .string(product.description),
            "category": .string(product.category),
            "brand": product.brand.map(JSON.string) ?? .null,
            "price": .double(product.price),
            "discountPercentage": .double(product.discountPercentage),
            "discountedPrice": .double(product.discountedPrice),
            "rating": .double(product.rating),
            "ratingBucket": .string(Self.ratingBuckets[product.ratingBucket] ?? ""),
            "stock": .int(Int(product.stock)),
            "stockStatus": .string(Self.stockStatuses[product.stockStatus] ?? ""),
            "thumbnail": .string(product.thumbnail),
            "tags": .array(product.tags.map(JSON.string))
        ])
    }

    private func error(_ code: String) -> JSON {
        .object(["error": .string(code)])
    }

    private func int(_ json: JSON?) -> Int {
        if case let .int(value) = json { value } else { 0 }
    }

    private func string(_ json: JSON?) -> String {
        if case let .string(value) = json { value } else { "" }
    }

    private func double(_ json: JSON) -> Double? {
        switch json {
        case let .double(value): value
        case let .int(value): Double(value)
        default: nil
        }
    }

    private static let sorts: [String: Sort] = [
        "id": .id, "price-asc": .priceAsc, "price-desc": .priceDesc, "rating-desc": .ratingDesc
    ]
    private static let failureCodes: [IngestFailure: String] = [
        .unknownRequest: "unknown-request", .invalidJson: "invalid-json",
        .notAProductPage: "not-a-product-page", .storageFailed: "storage-failed"
    ]
    private static let ratingBuckets: [RatingBucket: String] = [
        .excellent: "excellent", .good: "good", .fair: "fair", .poor: "poor"
    ]
    private static let stockStatuses: [StockStatus: String] = [
        .inStock: "in-stock", .lowStock: "low-stock", .outOfStock: "out-of-stock"
    ]
}
