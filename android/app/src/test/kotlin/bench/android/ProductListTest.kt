package bench.android

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.style.TextDecoration
import bench.android.core.CategoriesResult
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
    private val bed = summary(id = 3, title = "Annibale Colombo Bed", price = 1899.99)

    private fun noNetwork(request: FetchRequest): FetchResult = error("unexpected fetch of ${request.url}")

    private fun show(
        core: FakeSharedCore,
        categories: () -> CategoriesResult = core::categories,
        fetch: (FetchRequest) -> FetchResult = ::noNetwork,
    ) {
        compose.setContent {
            App({ productPages(core, fetch, pageSize = 2, it) }, core::detail, core::refresh, categories)
        }
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
    fun aFirstPageWhoseStorageReadFailsShowsCouldNotLoadWithRetry() {
        val core = FakeSharedCore(stored = listOf(mascara))
        core.storageFailing = true
        show(core)

        awaitText("Couldn't load products")
        compose.onNodeWithText("Retry").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(spinner).fetchSemanticsNodes().size)
    }

    @Test
    fun retryingOnceStorageRecoversShowsTheProducts() {
        val core = FakeSharedCore(stored = listOf(mascara))
        core.storageFailing = true
        show(core)
        awaitText("Couldn't load products")

        core.storageFailing = false
        compose.onNodeWithText("Retry").performClick()

        awaitText("Essence Mascara")
        awaitGone("Couldn't load products")
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

    @Test
    fun pullingDownRefreshesAndAProductRenamedOnTheServerShowsItsNewTitle() {
        val core = FakeSharedCore(remotePages = listOf(listOf(mascara)))
        show(core) { FetchResult.Response(200, ByteArray(0)) }
        awaitText("Essence Mascara")

        core.remotePages = listOf(listOf(mascara.copy(title = "Lash Princess Mascara")))
        pullDown()

        awaitText("Lash Princess Mascara")
        awaitGone("Essence Mascara")
    }

    @Test
    fun aProductTheServerNoLongerReturnsIsGoneAfterARefresh() {
        val core = FakeSharedCore(remotePages = listOf(listOf(mascara, palette)))
        show(core) { FetchResult.Response(200, ByteArray(0)) }
        awaitText("Eyeshadow Palette")

        core.remotePages = listOf(listOf(mascara))
        pullDown()

        awaitGone("Eyeshadow Palette")
        awaitText("Essence Mascara")
    }

    @Test
    fun aFailedRefreshShowsTheErrorWithRetryAndKeepsTheProductsShown() {
        val responses = ArrayDeque(listOf(FetchResult.Response(200, ByteArray(0)), FetchResult.NetworkError("offline")))
        show(FakeSharedCore(remotePages = listOf(listOf(mascara))), fetch = { responses.removeFirst() })
        awaitText("Essence Mascara")

        pullDown()

        awaitText("Network error: offline")
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Essence Mascara").assertIsDisplayed()
    }

    @Test
    fun choosingACategoryShowsOnlyItsProducts() {
        show(FakeSharedCore(stored = listOf(mascara, bed), categoryOf = mapOf(1L to "beauty", 3L to "furniture")))
        awaitText("Essence Mascara")

        choose("Category: All", "furniture")

        awaitText("Annibale Colombo Bed")
        awaitGone("Essence Mascara")
    }

    @Test
    fun aCategoryMenuWhoseReadFailsOffersNoCategoriesAndKeepsTheList() {
        show(
            FakeSharedCore(stored = listOf(mascara), categoryOf = mapOf(1L to "beauty")),
            categories = { CategoriesResult.StorageFailed },
        )
        awaitText("Essence Mascara")

        compose.onNodeWithText("Category: All").performClick()

        awaitText("Essence Mascara")
        assertEquals(0, compose.onAllNodesWithText("beauty").fetchSemanticsNodes().size)
    }

    @Test
    fun aPriceRangeShowsOnlyTheProductsPricedInsideIt() {
        show(FakeSharedCore(stored = listOf(mascara, palette, bed)))
        awaitText("Essence Mascara")

        type("Min price", "10")
        type("Max price", "100")

        awaitGone("Essence Mascara")
        awaitText("Eyeshadow Palette")
        awaitGone("Annibale Colombo Bed")
    }

    private fun type(
        field: String,
        text: String,
    ) = compose.onNode(hasSetTextAction() and hasText(field)).performTextReplacement(text)

    @Test
    fun clearingTheFiltersRestoresTheFullList() {
        show(
            FakeSharedCore(
                stored = listOf(mascara, palette, bed),
                categoryOf = mapOf(1L to "beauty", 3L to "furniture"),
            ),
        )
        choose("Category: All", "furniture")
        type("Max price", "100")
        awaitGone("Annibale Colombo Bed")

        compose.onNodeWithText("Clear filters").performClick()

        awaitText("Essence Mascara")
        awaitText("Annibale Colombo Bed")
    }

    @Test
    fun choosingPriceLowToHighReordersTheList() {
        val lipstick = summary(id = 4, title = "Red Lipstick", price = 12.99)
        show(FakeSharedCore(stored = listOf(bed, lipstick)))
        awaitText("Red Lipstick")

        choose("Sort: Default", "Price: low to high")

        compose.waitUntil { top("Red Lipstick") < top("Annibale Colombo Bed") }
    }

    private fun top(text: String) =
        compose
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .boundsInRoot.top

    private fun choose(
        menu: String,
        option: String,
    ) {
        compose.onNodeWithText(menu).performClick()
        compose.onNodeWithText(option).performClick()
    }

    private fun pullDown() = compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }

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

    private fun search(text: String) = type("Search", text)

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
