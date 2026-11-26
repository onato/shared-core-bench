package bench.contract

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContractRunnerTest {
    private val root: File = Files.createTempDirectory("contract").toFile()
    private val dir: File = File(root, "cases").apply { mkdir() }

    init {
        // FakeCore's detail echoes its step and args, so that echo is the product shape here.
        shapes("""{"detail": ["step", "args"]}""")
    }

    private fun caseFile(
        name: String,
        json: String,
    ) = File(dir, name).writeText(json)

    private fun shapes(json: String) = File(root, "shapes.json").writeText(json)

    /** Its `detail` returns the step's `returns` argument as the product. */
    private val productCore =
        object : ContractCore by FakeCore() {
            override fun detail(args: JsonObject): JsonElement = args.getValue("returns")
        }

    @Test
    fun eachCaseFileBecomesOneTestNamedAfterIt() {
        caseFile("paging.json", """{"steps": []}""")
        caseFile("decoding.json", """{"steps": []}""")
        caseFile("README.md", "not a case")

        val tests = ContractRunner.cases(dir) { FakeCore() }

        assertEquals(listOf("decoding", "paging"), tests.map { it.displayName })
    }

    @Test
    fun eachStepTypeReachesTheMatchingCoreCallAndPassesWhenResultsMatch() {
        caseFile(
            "all-steps.json",
            """
            {"steps": [
              {"step": "ingest", "args": {"requestId": 1}, "expect": {"step": "ingest", "args": {"requestId": 1}}},
              {"step": "page", "args": {"size": 20}, "expect": {"step": "page", "args": {"size": 20}}},
              {"step": "nextFetch", "expect": {"step": "nextFetch", "args": {}}},
              {"step": "detail", "args": {"id": 1}, "expect": {"step": "detail", "args": {"id": 1}}},
              {"step": "stats", "expect": {"step": "stats", "args": {}}},
              {"step": "refresh", "expect": {"step": "refresh", "args": {}}},
              {"step": "categories", "expect": {"step": "categories", "args": {}}}
            ]}
            """,
        )

        runOnly()
    }

    @Test
    fun aStepWhoseResultDiffersFailsShowingExpectedAndActual() {
        caseFile(
            "mismatch.json",
            """
            {"steps": [
              {"step": "page", "args": {"size": 1}, "expect": {"step": "page", "args": {"size": 1}}},
              {"step": "page", "args": {"size": 2}, "expect": {"step": "page", "args": {"size": 3}}}
            ]}
            """,
        )

        val failure = assertFailsWith<AssertionError> { runOnly() }

        assertEquals(
            """
            mismatch.json step 1 (page) returned a different result
            expected:
            {
                "step": "page",
                "args": {
                    "size": 3
                }
            }
            actual:
            {
                "step": "page",
                "args": {
                    "size": 2
                }
            }
            """.trimIndent(),
            failure.message,
        )
    }

    @Test
    fun anUnknownStepTypeFailsNamingTheFileAndStep() {
        caseFile(
            "typo.json",
            """
            {"steps": [
              {"step": "page", "expect": {"step": "page", "args": {}}},
              {"step": "detial", "expect": null}
            ]}
            """,
        )

        val failure = assertFailsWith<AssertionError> { runOnly() }

        assertEquals("typo.json step 1: unknown step type \"detial\"", failure.message)
    }

    @Test
    fun aDetailStepPassesWhenTheFieldsExpectListsMatchThoughAnotherFieldDiffers() {
        shapes("""{"detail": ["id", "title", "stock"]}""")
        caseFile(
            "listed-fields.json",
            """
            {"steps": [
              {"step": "detail", "args": {"returns": {"id": 1, "title": "Mug", "stock": 7}},
               "expect": {"id": 1, "stock": 7}}
            ]}
            """,
        )

        runOnly(productCore)
    }

    @Test
    fun aDetailStepFailsWhenAFieldExpectListsDiffersShowingOnlyTheListedFields() {
        shapes("""{"detail": ["id", "title", "stock"]}""")
        caseFile(
            "listed-field-differs.json",
            """
            {"steps": [
              {"step": "detail", "args": {"returns": {"id": 1, "title": "Mug", "stock": 7}},
               "expect": {"id": 1, "stock": 8}}
            ]}
            """,
        )

        val failure = assertFailsWith<AssertionError> { runOnly(productCore) }

        assertEquals(
            """
            listed-field-differs.json step 0 (detail) returned a different result
            expected:
            {
                "id": 1,
                "stock": 8
            }
            actual:
            {
                "id": 1,
                "stock": 7
            }
            """.trimIndent(),
            failure.message,
        )
    }

    @Test
    fun aDetailProductMissingAFieldShapesListsFailsThoughExpectDoesNotListIt() {
        shapes("""{"detail": ["id", "title", "stock"]}""")
        caseFile(
            "missing-field.json",
            """
            {"steps": [
              {"step": "detail", "args": {"returns": {"id": 1, "stock": 7}}, "expect": {"id": 1}}
            ]}
            """,
        )

        val failure = assertFailsWith<AssertionError> { runOnly(productCore) }

        assertEquals(
            "missing-field.json step 0 (detail) returned a product without the fields shapes.json lists: [title]",
            failure.message,
        )
    }

    @Test
    fun aDetailProductWithAFieldShapesDoesNotListFails() {
        shapes("""{"detail": ["id", "stock"]}""")
        caseFile(
            "extra-field.json",
            """
            {"steps": [
              {"step": "detail", "args": {"returns": {"id": 1, "stock": 7, "colour": "red"}}, "expect": {"id": 1}}
            ]}
            """,
        )

        val failure = assertFailsWith<AssertionError> { runOnly(productCore) }

        assertEquals(
            "extra-field.json step 0 (detail) returned a product with fields shapes.json does not list: [colour]",
            failure.message,
        )
    }

    @Test
    fun aDetailStepExpectingNullOrAnErrorIsComparedWhole() {
        shapes("""{"detail": ["id", "stock"]}""")
        caseFile(
            "error.json",
            """
            {"steps": [
              {"step": "detail", "args": {"returns": null}, "expect": null},
              {"step": "detail", "args": {"returns": {"error": "storage-failed"}}, "expect": {"error": "storage-failed"}},
              {"step": "detail", "args": {"returns": {"error": "storage-failed", "status": 500}},
               "expect": {"error": "storage-failed"}}
            ]}
            """,
        )

        val failure = assertFailsWith<AssertionError> { runOnly(productCore) }

        assertEquals(
            """
            error.json step 2 (detail) returned a different result
            expected:
            {
                "error": "storage-failed"
            }
            actual:
            {
                "error": "storage-failed",
                "status": 500
            }
            """.trimIndent(),
            failure.message,
        )
    }

    private fun runOnly(core: ContractCore = FakeCore()) =
        ContractRunner
            .cases(dir) { core }
            .single()
            .executable
            .execute()
}
