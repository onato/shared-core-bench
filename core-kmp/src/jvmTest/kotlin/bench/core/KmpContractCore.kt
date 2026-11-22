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
        val body = args.getValue("body").jsonPrimitive.content
        return when (val result = core.ingest(body.encodeToByteArray())) {
            is IngestResult.Decoded -> {
                JsonObject(
                    mapOf(
                        "accepted" to JsonPrimitive(result.accepted),
                        "rejected" to JsonPrimitive(result.rejected),
                    ),
                )
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

    override fun nextFetch(args: JsonObject): JsonElement = notInContractYet("nextFetch")

    override fun detail(args: JsonObject): JsonElement {
        val product = core.detail(args.getValue("id").jsonPrimitive.long) ?: return JsonNull
        return productJson(product)
    }

    private fun productJson(product: Product) =
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

    private fun notInContractYet(step: String): Nothing = error("$step is not implemented by the KMP core yet")
}
