package bench.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal data class ProductRow(
    val id: Long,
    val title: String,
    val price: Double,
)

internal sealed interface PageDecoding {
    data class Products(
        val rows: List<ProductRow>,
        val rejected: Int,
    ) : PageDecoding

    data class Unreadable(
        val error: IngestResult,
    ) : PageDecoding
}

internal fun decodePage(body: ByteArray): PageDecoding {
    val page = parseOrNull(body)
    val products = (page as? JsonObject)?.get("products") as? JsonArray
    return when {
        page == null -> PageDecoding.Unreadable(IngestResult.InvalidJson)
        products == null -> PageDecoding.Unreadable(IngestResult.NotAProductPage)
        else -> decodeProducts(products)
    }
}

private fun parseOrNull(body: ByteArray): JsonElement? =
    try {
        Json.parseToJsonElement(body.decodeToString())
    } catch (_: SerializationException) {
        null
    }

private fun decodeProducts(products: JsonArray): PageDecoding.Products {
    val rows = products.mapNotNull(::productOrNull)
    return PageDecoding.Products(rows = rows, rejected = products.size - rows.size)
}

private fun productOrNull(item: JsonElement): ProductRow? {
    val fields = item as? JsonObject
    val id = number(fields?.get("id"))?.longOrNull
    val title = text(fields?.get("title"))
    val price = number(fields?.get("price"))?.doubleOrNull
    return if (id != null && title != null && price != null) ProductRow(id, title, price) else null
}

private fun number(field: JsonElement?): JsonPrimitive? = (field as? JsonPrimitive)?.takeUnless { it.isString }

private fun text(field: JsonElement?): String? = (field as? JsonPrimitive)?.takeIf { it.isString }?.content
