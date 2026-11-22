package bench.core

import bench.contract.ContractCore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

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
                JsonObject(mapOf("error" to JsonPrimitive("invalid-json")))
            }
        }
    }

    override fun page(args: JsonObject): JsonElement = notInContractYet("page")

    override fun nextFetch(args: JsonObject): JsonElement = notInContractYet("nextFetch")

    override fun stats(args: JsonObject): JsonElement = notInContractYet("stats")

    private fun notInContractYet(step: String): Nothing = error("$step is not implemented by the KMP core yet")
}
