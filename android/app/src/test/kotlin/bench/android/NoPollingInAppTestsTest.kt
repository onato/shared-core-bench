package bench.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NoPollingInAppTestsTest {
    // Split so this file doesn't find itself.
    private val polling = "wait" + "Until"
    private val tests = File("src/test")

    @Test
    fun noAppTestPollsWithWaitUntil() {
        require(tests.isDirectory) { "run from the app module: no ${tests.absolutePath}" }
        val polls =
            tests
                .walk()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> polling in line }
                        .map { (index, _) -> "${file.path}:${index + 1}" }
                }.toList()

        assertEquals(emptyList<String>(), polls)
    }
}
