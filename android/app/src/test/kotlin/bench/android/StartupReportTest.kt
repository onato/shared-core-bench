package bench.android

import androidx.activity.FullyDrawnReporter
import androidx.activity.FullyDrawnReporterOwner
import androidx.activity.compose.LocalFullyDrawnReporterOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import bench.android.core.FakeSharedCore
import bench.android.core.FetchRequest
import bench.android.core.summary
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Macrobenchmark's cold start ends at `reportFullyDrawn`, so the app reports it once products are on screen. */
@RunWith(RobolectricTestRunner::class)
class StartupReportTest {
    val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DrainMainLooper()).around(compose)

    // Slow enough that, were the load on a thread Compose's test rule can't see, waitForIdle would return first.
    private val slowNetwork: (FetchRequest) -> FetchResult = {
        Thread.sleep(SLOW_FETCH_MILLIS)
        FetchResult.Response(200, ByteArray(0))
    }

    // An activity's reporter waits for a draw, which Robolectric never dispatches; this one reports at once.
    private val owner =
        object : FullyDrawnReporterOwner {
            override val fullyDrawnReporter = FullyDrawnReporter(Runnable::run) {}
        }

    @Test
    fun theAppReportsFullyDrawnOnceTheFirstPageIsShown() {
        val core =
            FakeSharedCore(remotePages = listOf(listOf(summary(id = 1, title = "Essence Mascara", price = 9.99))))

        compose.setContent {
            CompositionLocalProvider(LocalFullyDrawnReporterOwner provides owner) {
                App(
                    { productPages(core, slowNetwork, pageSize = 20, it, Dispatchers.Main) },
                    core::detail,
                    core::refresh,
                    core::categories,
                )
            }
        }

        compose.waitForIdle()

        assertEquals(true, owner.fullyDrawnReporter.isFullyDrawnReported)
    }

    private companion object {
        const val SLOW_FETCH_MILLIS = 200L
    }
}
