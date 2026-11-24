package bench.android.core

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

/** Speaks the contract fixtures' JSON to any [SharedCore]. */
class SharedCoreContract(
    private val core: SharedCore,
) : ContractCore {
    override fun ingest(args: JsonObject): JsonElement {
        val requestId = args.getValue("requestId").jsonPrimitive.long
        val status = args.getValue("status").jsonPrimitive.int
        val body = args.getValue("body").jsonPrimitive.content
        return when (val result = core.ingest(requestId, status, body.encodeToByteArray())) {
            is IngestResult.Ingested -> json("accepted" to result.accepted, "rejected" to result.rejected)
            is IngestResult.HttpStatus -> json("error" to "http-status", "status" to result.status)
            is IngestResult.Failed -> json("error" to FAILURE_CODES.getValue(result.reason))
        }
    }

    override fun page(args: JsonObject): JsonElement {
        val cursor = args["cursor"]?.jsonPrimitive?.longOrNull
        return when (val result = core.page(cursor, args.getValue("size").jsonPrimitive.int)) {
            is PageResult.Page -> {
                json("items" to JsonArray(result.items.map(::productJson)), "nextCursor" to result.nextCursor)
            }

            PageResult.InvalidSize -> {
                json("error" to "invalid-page-size")
            }
        }
    }

    override fun nextFetch(args: JsonObject): JsonElement {
        val request = core.nextFetch() ?: return JsonNull
        return json("requestId" to request.requestId, "url" to request.url)
    }

    override fun detail(args: JsonObject): JsonElement {
        val product = core.detail(args.getValue("id").jsonPrimitive.long) ?: return JsonNull
        return with(product) {
            json(
                "id" to id,
                "title" to title,
                "description" to description,
                "category" to category,
                "brand" to brand,
                "price" to price,
                "discountPercentage" to discountPercentage,
                "discountedPrice" to discountedPrice,
                "rating" to rating,
                "ratingBucket" to RATING_BUCKETS.getValue(ratingBucket),
                "stock" to stock,
                "stockStatus" to STOCK_STATUSES.getValue(stockStatus),
                "thumbnail" to thumbnail,
                "tags" to JsonArray(tags.map(::JsonPrimitive)),
            )
        }
    }

    override fun stats(args: JsonObject): JsonElement = json("productCount" to core.stats().productCount)

    private fun productJson(it: ProductSummary) = json("id" to it.id, "title" to it.title, "price" to it.price)

    private fun json(vararg fields: Pair<String, Any?>): JsonObject {
        val elements = fields.associate { (key, value) -> key to element(value) }
        return JsonObject(elements)
    }

    private fun element(value: Any?): JsonElement =
        when (value) {
            is JsonElement -> value
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> JsonNull
        }

    private companion object {
        val FAILURE_CODES =
            mapOf(
                IngestFailure.UnknownRequest to "unknown-request",
                IngestFailure.InvalidJson to "invalid-json",
                IngestFailure.NotAProductPage to "not-a-product-page",
                IngestFailure.StorageFailed to "storage-failed",
            )
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
