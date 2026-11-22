package bench.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal fun decodePage(body: ByteArray): IngestResult {
    val products =
        Json
            .parseToJsonElement(body.decodeToString())
            .jsonObject
            .getValue("products")
            .jsonArray
    return IngestResult.Decoded(accepted = products.size, rejected = 0)
}
