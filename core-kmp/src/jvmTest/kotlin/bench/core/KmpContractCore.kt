package bench.core

import bench.contract.ContractCore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

class KmpContractCore(
    private val core: Core,
) : ContractCore {
    override fun ingest(args: JsonObject): JsonElement {
        val requestId = args.getValue("requestId").jsonPrimitive.long
        val status = args.getValue("status").jsonPrimitive.int
        val body = args.getValue("body").jsonPrimitive.content
        return when (val result = core.ingest(requestId, status, body.encodeToByteArray())) {
            is IngestResult.Decoded -> {
                JsonObject(
                    mapOf(
                        "accepted" to JsonPrimitive(result.accepted),
                        "rejected" to JsonPrimitive(result.rejected),
                    ),
                )
            }

            IngestResult.UnknownRequest -> {
                errorResult("unknown-request")
            }

            is IngestResult.HttpStatus -> {
                JsonObject(mapOf("error" to JsonPrimitive("http-status"), "status" to JsonPrimitive(result.status)))
            }

            IngestResult.InvalidJson -> {
                errorResult("invalid-json")
            }

            IngestResult.NotAProductPage -> {
                errorResult("not-a-product-page")
            }

            IngestResult.StorageFailed -> {
                errorResult("storage-failed")
            }
        }
    }

    private fun errorResult(code: String) = JsonObject(mapOf("error" to JsonPrimitive(code)))

    override fun page(args: JsonObject): JsonElement {
        val cursor = args["cursor"]?.jsonPrimitive?.longOrNull
        return when (val result = core.page(cursor, args.getValue("size").jsonPrimitive.int)) {
            is PageResult.Page -> {
                JsonObject(
                    mapOf(
                        "items" to JsonArray(result.items.map(::productJson)),
                        "nextCursor" to JsonPrimitive(result.nextCursor),
                    ),
                )
            }

            PageResult.InvalidSize -> {
                errorResult("invalid-page-size")
            }
        }
    }

    override fun nextFetch(args: JsonObject): JsonElement {
        val request = core.nextFetch() ?: return JsonNull
        return JsonObject(mapOf("requestId" to JsonPrimitive(request.requestId), "url" to JsonPrimitive(request.url)))
    }

    override fun detail(args: JsonObject): JsonElement {
        val product = core.detail(args.getValue("id").jsonPrimitive.long) ?: return JsonNull
        return JsonObject(
            mapOf(
                "id" to JsonPrimitive(product.id),
                "title" to JsonPrimitive(product.title),
                "description" to JsonPrimitive(product.description),
                "category" to JsonPrimitive(product.category),
                "brand" to JsonPrimitive(product.brand),
                "price" to JsonPrimitive(product.price),
                "discountPercentage" to JsonPrimitive(product.discountPercentage),
                "discountedPrice" to JsonPrimitive(product.discountedPrice),
                "rating" to JsonPrimitive(product.rating),
                "ratingBucket" to JsonPrimitive(RATING_BUCKETS.getValue(product.ratingBucket)),
                "stock" to JsonPrimitive(product.stock),
                "stockStatus" to JsonPrimitive(STOCK_STATUSES.getValue(product.stockStatus)),
                "thumbnail" to JsonPrimitive(product.thumbnail),
                "tags" to JsonArray(product.tags.map(::JsonPrimitive)),
            ),
        )
    }

    private fun productJson(product: ProductSummary) =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(product.id),
                "title" to JsonPrimitive(product.title),
                "price" to JsonPrimitive(product.price),
            ),
        )

    override fun stats(args: JsonObject): JsonElement {
        val stats = core.stats()
        return JsonObject(mapOf("productCount" to JsonPrimitive(stats.productCount)))
    }

    private companion object {
        val RATING_BUCKETS =
            mapOf(
                RatingBucket.Excellent to "excellent",
                RatingBucket.Good to "good",
                RatingBucket.Fair to "fair",
                RatingBucket.Poor to "poor",
            )
        val STOCK_STATUSES =
            mapOf(
                StockStatus.InStock to "in-stock",
                StockStatus.LowStock to "low-stock",
                StockStatus.OutOfStock to "out-of-stock",
            )
    }
}
