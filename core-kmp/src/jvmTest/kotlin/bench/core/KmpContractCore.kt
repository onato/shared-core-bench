package bench.core

import bench.contract.ContractCore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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

    override fun page(args: JsonObject): JsonElement = notInContractYet("page")

    override fun nextFetch(args: JsonObject): JsonElement = notInContractYet("nextFetch")

    override fun detail(args: JsonObject): JsonElement {
        val product = core.detail(args.getValue("id").jsonPrimitive.long) ?: return JsonNull
        return JsonObject(
            mapOf(
                "id" to JsonPrimitive(product.id),
                "title" to JsonPrimitive(product.title),
                "price" to JsonPrimitive(product.price),
            ),
        )
    }

    override fun stats(args: JsonObject): JsonElement {
        val stats = core.stats()
        return JsonObject(mapOf("productCount" to JsonPrimitive(stats.productCount)))
    }

    private fun notInContractYet(step: String): Nothing = error("$step is not implemented by the KMP core yet")
}
