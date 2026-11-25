package bench.android

import androidx.activity.FullyDrawnReporter
import androidx.activity.FullyDrawnReporterOwner
import androidx.activity.compose.LocalFullyDrawnReporterOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import bench.android.core.FakeSharedCore
import bench.android.core.FetchRequest
import bench.android.core.summary
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

    private fun noNetwork(request: FetchRequest): FetchResult = error("unexpected fetch of ${request.url}")

    // An activity's reporter waits for a draw, which Robolectric never dispatches; this one reports at once.
    private val owner =
        object : FullyDrawnReporterOwner {
            override val fullyDrawnReporter = FullyDrawnReporter(Runnable::run) {}
        }

    @Test
    fun theAppReportsFullyDrawnOnceTheFirstPageIsShown() {
        val core = FakeSharedCore(stored = listOf(summary(id = 1, title = "Essence Mascara", price = 9.99)))

        compose.setContent {
            CompositionLocalProvider(LocalFullyDrawnReporterOwner provides owner) {
                App(
                    { productPages(core, ::noNetwork, pageSize = 20, it) },
                    core::detail,
                    core::refresh,
                    core::categories,
                )
            }
        }

        compose.waitUntil { owner.fullyDrawnReporter.isFullyDrawnReported }
    }
}
