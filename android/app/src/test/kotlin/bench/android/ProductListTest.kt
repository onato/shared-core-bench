package bench.android

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import bench.android.core.FakeSharedCore
import bench.android.core.FetchRequest
import bench.android.core.ProductSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
class ProductListTest {
    @get:Rule
    val compose = createComposeRule()

    private val mascara = ProductSummary(id = 1, title = "Essence Mascara", price = 9.99)
    private val palette = ProductSummary(id = 2, title = "Eyeshadow Palette", price = 19.0)

    private fun noNetwork(request: FetchRequest): FetchResult = error("unexpected fetch of ${request.url}")

    private fun show(
        core: FakeSharedCore,
        fetch: (FetchRequest) -> FetchResult = ::noNetwork,
    ) {
        compose.setContent { App(productPages(core, fetch, pageSize = 2)) }
    }

    @Test
    fun theScreenIsTitledProducts() {
        show(FakeSharedCore())

        awaitText("Products")
    }

    @Test
    fun theFirstPageOfProductsIsShown() {
        show(FakeSharedCore(stored = listOf(mascara, palette)))

        awaitText("Essence Mascara")
        awaitText("Eyeshadow Palette")
    }

    @Test
    fun scrollingToTheEndLoadsMoreProducts() {
        show(FakeSharedCore(stored = (1L..30L).map { ProductSummary(id = it, title = "Product $it", price = 1.0) }))

        awaitText("Product 1")
        assertEquals(0, compose.onAllNodesWithText("Product 30").fetchSemanticsNodes().size)
        scrollUntilShown("Product 30")

        awaitText("Product 30")
    }

    @Test
    fun aFailedLoadShowsRetryAndRetryingRecovers() {
        val responses = ArrayDeque(listOf(FetchResult.NetworkError("timeout"), FetchResult.Response(200, ByteArray(0))))
        show(FakeSharedCore(remotePages = listOf(listOf(mascara))), fetch = { responses.removeFirst() })

        awaitText("Network error: timeout")
        compose.onNodeWithText("Retry").performClick()

        awaitText("Essence Mascara")
        assertEquals(0, compose.onAllNodesWithText("Retry").fetchSemanticsNodes().size)
    }

    @Test
    fun anEmptyCatalogueSaysThereAreNoProducts() {
        show(FakeSharedCore())

        awaitText("No products")
    }

    @Test
    fun theEmptyMessageIsNotShownWhileProductsAreListed() {
        show(FakeSharedCore(stored = listOf(mascara)))

        awaitText("Essence Mascara")
        assertEquals(0, compose.onAllNodesWithText("No products").fetchSemanticsNodes().size)
    }

    @Test
    fun aSlowRemotePageShowsLoadingOffTheMainThreadUntilItArrives() {
        val released = CountDownLatch(1)
        show(FakeSharedCore(remotePages = listOf(listOf(mascara)))) {
            released.await()
            FetchResult.Response(200, ByteArray(0))
        }

        compose.onNode(spinner).assertIsDisplayed()
        released.countDown()

        awaitText("Essence Mascara")
        assertEquals(0, compose.onAllNodes(spinner).fetchSemanticsNodes().size)
    }

    private val spinner = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    private fun awaitText(text: String) {
        compose.waitUntil { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun scrollUntilShown(text: String) {
        repeat(MAX_SWIPES) {
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
            compose.waitUntil { compose.onAllNodes(spinner).fetchSemanticsNodes().isEmpty() }
        }
    }

    private companion object {
        const val MAX_SWIPES = 20
    }
}
