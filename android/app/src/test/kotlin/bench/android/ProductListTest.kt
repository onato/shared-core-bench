package bench.android

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.style.TextDecoration
import bench.android.core.FakeSharedCore
import bench.android.core.FetchRequest
import bench.android.core.ProductSummary
import bench.android.core.summary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
class ProductListTest {
    val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DrainMainLooper()).around(compose)

    private val mascara = summary(id = 1, title = "Essence Mascara", price = 9.99)
    private val palette = summary(id = 2, title = "Eyeshadow Palette", price = 19.0)

    private fun noNetwork(request: FetchRequest): FetchResult = error("unexpected fetch of ${request.url}")

    private fun show(
        core: FakeSharedCore,
        fetch: (FetchRequest) -> FetchResult = ::noNetwork,
    ) {
        compose.setContent { App({ productPages(core, fetch, pageSize = 2, it) }, core::detail) }
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
        show(FakeSharedCore(stored = (1L..30L).map { summary(id = it, title = "Product $it", price = 1.0) }))

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

    @Test
    fun aDiscountedProductShowsItsDiscountedPriceAndItsOriginalPrice() {
        show(FakeSharedCore(stored = listOf(cell(price = 9.99, discountedPrice = 9.24))))

        awaitText("$9.24")
        assertEquals(listOf(TextDecoration.LineThrough), decorationsOf("$9.99"))
    }

    @Test
    fun aProductWithNoDiscountShowsOnePrice() {
        show(FakeSharedCore(stored = listOf(cell(price = 19.0, discountedPrice = 19.0))))

        awaitText("$19.00")
        assertEquals(1, compose.onAllNodesWithText("$19.00", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theRatingShowsWithOneDecimalPlace() {
        show(FakeSharedCore(stored = listOf(cell(rating = 3.28))))

        awaitText("3.3")
    }

    @Test
    fun typingPartOfATitleShowsOnlyMatchingProducts() {
        show(FakeSharedCore(stored = listOf(mascara, palette)))
        awaitText("Eyeshadow Palette")

        search("mascara")

        awaitText("Essence Mascara")
        awaitGone("Eyeshadow Palette")
    }

    @Test
    fun clearingTheSearchShowsEveryProductAgain() {
        show(FakeSharedCore(stored = listOf(mascara, palette)))
        search("mascara")
        awaitGone("Eyeshadow Palette")

        search("")

        awaitText("Essence Mascara")
        awaitText("Eyeshadow Palette")
    }

    @Test
    fun aSearchWithNoMatchSaysNoProductsMatch() {
        show(FakeSharedCore(stored = listOf(mascara, palette)))
        awaitText("Essence Mascara")

        search("lipstick")

        awaitText("No products match")
    }

    private fun cell(
        price: Double = 19.0,
        discountedPrice: Double = price,
        rating: Double = 4.5,
    ) = ProductSummary(1, "Eyeshadow Palette", price, discountedPrice, rating, thumbnail = "")

    private fun decorationsOf(text: String) =
        compose
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
            .spanStyles
            .map { it.item.textDecoration }

    private val spinner = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    private fun awaitText(text: String) {
        compose.waitUntil { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun search(text: String) = compose.onNode(hasSetTextAction()).performTextReplacement(text)

    private fun awaitGone(text: String) {
        compose.waitUntil { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
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
