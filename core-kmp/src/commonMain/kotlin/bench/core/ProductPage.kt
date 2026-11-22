package bench.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal fun decodePage(body: ByteArray): IngestResult {
    val page = parseOrNull(body)
    val products = (page as? JsonObject)?.get("products") as? JsonArray
    return when {
        page == null -> IngestResult.InvalidJson
        products == null -> IngestResult.NotAProductPage
        else -> countValid(products)
    }
}

private fun parseOrNull(body: ByteArray): JsonElement? =
    try {
        Json.parseToJsonElement(body.decodeToString())
    } catch (_: SerializationException) {
        null
    }

private fun countValid(products: JsonArray): IngestResult.Decoded {
    val accepted = products.count(::isValidProduct)
    return IngestResult.Decoded(accepted = accepted, rejected = products.size - accepted)
}

private fun isValidProduct(item: JsonElement): Boolean {
    val price = (item as? JsonObject)?.get("price") as? JsonPrimitive ?: return false
    return !price.isString && price.doubleOrNull != null
}
