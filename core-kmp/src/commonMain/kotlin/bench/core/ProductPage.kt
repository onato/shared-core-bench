package bench.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun decodePage(body: ByteArray): IngestResult {
    val page =
        try {
            Json.parseToJsonElement(body.decodeToString())
        } catch (_: SerializationException) {
            return IngestResult.InvalidJson
        }
    val products = page.jsonObject.getValue("products").jsonArray
    val accepted = products.count(::isValidProduct)
    return IngestResult.Decoded(accepted = accepted, rejected = products.size - accepted)
}

private fun isValidProduct(item: JsonElement): Boolean {
    val price = item.jsonObject["price"] ?: return false
    return price.jsonPrimitive.doubleOrNull != null
}
