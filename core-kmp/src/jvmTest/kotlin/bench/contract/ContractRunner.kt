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

    fun cases(
        dir: File,
        newCore: () -> ContractCore,
    ): List<DynamicTest> =
        dir
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .map { file -> DynamicTest.dynamicTest(file.nameWithoutExtension) { run(file, newCore()) } }

    private fun run(
        file: File,
        core: ContractCore,
    ) {
        steps(file).forEachIndexed { index, element ->
            val step = element.jsonObject
            val type = step.getValue("step").jsonPrimitive.content
            val args = step["args"]?.jsonObject ?: JsonObject(emptyMap())
            val actual = call(core, type, args) ?: throw unknownStep("${file.name} step $index", type)
            val expected = step.getValue("expect")
            if (actual != expected) {
                throw mismatch("${file.name} step $index ($type)", expected, actual)
            }
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
            "stats" -> core.stats(args)
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
