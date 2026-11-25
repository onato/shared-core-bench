package bench.macrobenchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Workload 11: fling through the whole list from an empty cache, timing every frame, paging included. */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scroll1000Products() =
        rule.measureRepeated(
            packageName = APP_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            setupBlock = {
                device.executeShellCommand("pm clear $APP_PACKAGE")
                startActivityAndWait(fixtureIntent(RECORDS))
                device.waitForFirstProduct()
            },
        ) {
            val list = device.findObject(By.scrollable(true))
            list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
            // fling also stops at the next page's spinner; the list ends when it can't move and nothing loads.
            while (list.fling(Direction.DOWN) || device.hasObject(LOADING)) {
                device.wait(Until.gone(LOADING), PAGE_TIMEOUT_MS)
            }
        }
}

private const val GESTURE_MARGIN_FRACTION = 5
private const val PAGE_TIMEOUT_MS = 10_000L
private val LOADING = By.clazz("android.widget.ProgressBar")
