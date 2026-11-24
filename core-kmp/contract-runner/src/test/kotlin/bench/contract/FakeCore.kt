package bench.contract

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Answers every step with the step's name and the arguments it was given. */
class FakeCore : ContractCore {
    override fun ingest(args: JsonObject): JsonElement = echo("ingest", args)

    override fun page(args: JsonObject): JsonElement = echo("page", args)

    override fun nextFetch(args: JsonObject): JsonElement = echo("nextFetch", args)

    override fun detail(args: JsonObject): JsonElement = echo("detail", args)

    override fun stats(args: JsonObject): JsonElement = echo("stats", args)

    override fun refresh(args: JsonObject): JsonElement = echo("refresh", args)

    private fun echo(
        step: String,
        args: JsonObject,
    ) = JsonObject(mapOf("step" to JsonPrimitive(step), "args" to args))
}
