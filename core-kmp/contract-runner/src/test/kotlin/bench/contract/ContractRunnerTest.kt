package bench.contract

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContractRunnerTest {
    private val dir: File = Files.createTempDirectory("contract").toFile()

    private fun fixture(
        name: String,
        json: String,
    ) = File(dir, name).writeText(json)

    @Test
    fun eachFixtureFileBecomesOneTestNamedAfterIt() {
        fixture("paging.json", """{"steps": []}""")
        fixture("decoding.json", """{"steps": []}""")
        fixture("README.md", "not a case")

        val tests = ContractRunner.cases(dir) { FakeCore() }

        assertEquals(listOf("decoding", "paging"), tests.map { it.displayName })
    }

    @Test
    fun eachStepTypeReachesTheMatchingCoreCallAndPassesWhenResultsMatch() {
        fixture(
            "all-steps.json",
            """
            {"steps": [
              {"step": "ingest", "args": {"requestId": 1}, "expect": {"step": "ingest", "args": {"requestId": 1}}},
              {"step": "page", "args": {"size": 20}, "expect": {"step": "page", "args": {"size": 20}}},
              {"step": "nextFetch", "expect": {"step": "nextFetch", "args": {}}},
              {"step": "detail", "args": {"id": 1}, "expect": {"step": "detail", "args": {"id": 1}}},
              {"step": "stats", "expect": {"step": "stats", "args": {}}},
              {"step": "refresh", "expect": {"step": "refresh", "args": {}}}
            ]}
            """,
        )

        runOnly()
    }

    @Test
    fun aStepWhoseResultDiffersFailsShowingExpectedAndActual() {
        fixture(
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
        fixture(
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

    private fun runOnly() =
        ContractRunner
            .cases(dir) { FakeCore() }
            .single()
            .executable
            .execute()
}
