package bench.contract

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface ContractCore {
    fun ingest(args: JsonObject): JsonElement

    fun page(args: JsonObject): JsonElement

    fun nextFetch(args: JsonObject): JsonElement

    fun detail(args: JsonObject): JsonElement

    fun stats(args: JsonObject): JsonElement

    fun refresh(args: JsonObject): JsonElement
}
