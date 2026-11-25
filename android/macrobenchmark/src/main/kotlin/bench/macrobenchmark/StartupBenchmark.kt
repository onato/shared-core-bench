package bench.macrobenchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Workload 10: cold start to the first page, which the app marks with `reportFullyDrawn`. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartFromCache1000Products() =
        rule.measureRepeated(
            packageName = APP_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            setupBlock = {
                if (iteration == 0) {
                    device.executeShellCommand("pm clear $APP_PACKAGE")
                    startActivityAndWait(fixtureIntent(RECORDS))
                    device.waitForFirstProduct()
                    killProcess()
                }
            },
        ) {
            startActivityAndWait(fixtureIntent(RECORDS))
            device.waitForFirstProduct()
        }

    @Test
    fun coldStartEmptyCache1000Products() =
        rule.measureRepeated(
            packageName = APP_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            setupBlock = { device.executeShellCommand("pm clear $APP_PACKAGE") },
        ) {
            startActivityAndWait(fixtureIntent(RECORDS))
            device.waitForFirstProduct()
        }
}
