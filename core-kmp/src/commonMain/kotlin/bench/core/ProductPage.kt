package bench.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class Product(
    val id: Long,
    val title: String,
    val price: Double,
)

internal sealed interface PageDecoding {
    data class Products(
        val rows: List<ProductDetail>,
        val rejected: Int,
        val total: Long?,
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
        else -> decodeProducts(products, total = number(page["total"])?.longOrNull)
    }
}

private fun parseOrNull(body: ByteArray): JsonElement? =
    try {
        Json.parseToJsonElement(body.decodeToString())
    } catch (_: SerializationException) {
        null
    }

private fun decodeProducts(
    products: JsonArray,
    total: Long?,
): PageDecoding.Products {
    val rows = products.mapNotNull(::productOrNull)
    return PageDecoding.Products(rows = rows, rejected = products.size - rows.size, total = total)
}

private fun productOrNull(item: JsonElement): ProductDetail? {
    val fields = ProductFields(item as? JsonObject ?: return null)
    val product =
        ProductDetail(
            id = fields.integer("id"),
            title = fields.string("title"),
            description = fields.string("description"),
            category = fields.string("category"),
            brand = fields.optionalString("brand"),
            price = fields.decimal("price"),
            discountPercentage = fields.decimal("discountPercentage"),
            rating = fields.decimal("rating"),
            stock = fields.integer("stock"),
            thumbnail = fields.string("thumbnail"),
            tags = fields.strings("tags"),
        )
    return product.takeIf { fields.complete }
}

/** Reads a product's fields, standing in a placeholder for each one that is missing or mistyped. */
private class ProductFields(
    private val fields: JsonObject,
) {
    var complete = true
        private set

    fun string(name: String): String = text(fields[name]) ?: missing("")

    fun optionalString(name: String): String? = text(fields[name])

    fun decimal(name: String): Double = number(fields[name])?.doubleOrNull ?: missing(0.0)

    fun integer(name: String): Long = number(fields[name])?.longOrNull ?: missing(0L)

    fun strings(name: String): List<String> {
        val items = fields[name] as? JsonArray
        val strings = items?.mapNotNull(::text)
        return strings?.takeIf { it.size == items.size } ?: missing(emptyList())
    }

    private fun <T> missing(placeholder: T): T {
        complete = false
        return placeholder
    }
}

private fun number(field: JsonElement?): JsonPrimitive? = (field as? JsonPrimitive)?.takeUnless { it.isString }

private fun text(field: JsonElement?): String? = (field as? JsonPrimitive)?.takeIf { it.isString }?.content
