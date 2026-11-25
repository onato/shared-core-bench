package bench.macrobenchmark

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith

/** Harness check: the launch the benchmarks measure reaches a rendered product, on a small fixture. */
@RunWith(AndroidJUnit4::class)
class FixtureStartupTest {
    @Test
    fun fixtureModeLaunchRendersTheFirstPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("pm clear $APP_PACKAGE")

        instrumentation.context.startActivity(fixtureIntent(records = 20).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        device.waitForFirstProduct()
    }
}
