package bench.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.opentest4j.AssertionFailedError
import java.io.File

object ContractRunner {
    private val pretty = Json { prettyPrint = true }

    /** Runs each case in [dir], checking `detail` products against the `shapes.json` beside it. */
    fun cases(
        dir: File,
        newCore: () -> ContractCore,
    ): List<DynamicTest> {
        val detailFields = detailFields(File(dir.parentFile, "shapes.json"))
        return dir
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .map { file -> DynamicTest.dynamicTest(file.nameWithoutExtension) { run(file, newCore(), detailFields) } }
    }

    private fun detailFields(shapes: File): Set<String> =
        Json
            .parseToJsonElement(shapes.readText())
            .jsonObject
            .getValue("detail")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

    private fun run(
        file: File,
        core: ContractCore,
        detailFields: Set<String>,
    ) {
        steps(file).forEachIndexed { index, element ->
            val step = element.jsonObject
            val type = step.getValue("step").jsonPrimitive.content
            val args = step["args"]?.jsonObject ?: JsonObject(emptyMap())
            val actual = call(core, type, args) ?: throw unknownStep("${file.name} step $index", type)
            val where = "${file.name} step $index ($type)"
            if (type == "detail") {
                product(actual)?.let { checkShape(where, detailFields, it) }
            }
            val expected = step.getValue("expect")
            val compared = compared(type, expected, actual)
            if (compared != expected) {
                throw mismatch(where, expected, compared)
            }
        }
    }

    /** A `detail` product is compared on the fields `expect` lists; every other result whole. */
    private fun compared(
        type: String,
        expected: JsonElement,
        actual: JsonElement,
    ): JsonElement {
        val listed = product(expected)
        return if (type == "detail" && listed != null && actual is JsonObject) {
            JsonObject(actual.filterKeys { it in listed })
        } else {
            actual
        }
    }

    /** A `detail` result other than `null` and `{"error": …}`. */
    private fun product(result: JsonElement): JsonObject? = (result as? JsonObject)?.takeIf { "error" !in it }

    private fun checkShape(
        where: String,
        fields: Set<String>,
        product: JsonObject,
    ) {
        val missing = fields - product.keys
        if (missing.isNotEmpty()) {
            throw AssertionError("$where returned a product without the fields shapes.json lists: $missing")
        }
        val extra = product.keys - fields
        if (extra.isNotEmpty()) {
            throw AssertionError("$where returned a product with fields shapes.json does not list: $extra")
        }
    }

    private fun steps(file: File) =
        Json
            .parseToJsonElement(file.readText())
            .jsonObject
            .getValue("steps")
            .jsonArray

    private fun call(
        core: ContractCore,
        type: String,
        args: JsonObject,
    ): JsonElement? =
        when (type) {
            "ingest" -> core.ingest(args)
            "page" -> core.page(args)
            "nextFetch" -> core.nextFetch(args)
            "detail" -> core.detail(args)
            "stats" -> core.stats(args)
            "refresh" -> core.refresh(args)
            "categories" -> core.categories(args)
            else -> null
        }

    private fun unknownStep(
        where: String,
        type: String,
    ) = AssertionError("$where: unknown step type \"$type\"")

    private fun mismatch(
        where: String,
        expected: JsonElement,
        actual: JsonElement,
    ): AssertionFailedError {
        val expectedText = pretty.encodeToString(JsonElement.serializer(), expected)
        val actualText = pretty.encodeToString(JsonElement.serializer(), actual)
        return AssertionFailedError(
            "$where returned a different result\nexpected:\n$expectedText\nactual:\n$actualText",
            expectedText,
            actualText,
        )
    }
}
