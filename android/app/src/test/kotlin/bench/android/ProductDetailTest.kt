package bench.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import bench.android.core.FakeSharedCore
import bench.android.core.ProductDetail
import bench.android.core.RatingBucket
import bench.android.core.StockStatus
import bench.android.core.summary
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductDetailTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mascara =
        ProductDetail(
            id = 1,
            title = "Essence Mascara",
            description = "Lengthens and volumises lashes",
            category = "beauty",
            brand = "Essence",
            price = 10.0,
            discountPercentage = 10.0,
            discountedPrice = 9.0,
            rating = 4.94,
            ratingBucket = RatingBucket.Excellent,
            stock = 3,
            stockStatus = StockStatus.LowStock,
            thumbnail = "",
            tags = listOf("mascara", "lashes"),
        )

    private fun show(
        core: FakeSharedCore,
        pageSize: Int = 2,
    ) {
        compose.setContent {
            App(
                {
                    productPages(
                        core,
                        { request -> error("unexpected fetch of ${request.url}") },
                        pageSize,
                        it,
                        Dispatchers.Main,
                    )
                },
                core::detail,
                core::refresh,
                core::categories,
            )
        }
    }

    private fun stocked(vararg details: ProductDetail) =
        FakeSharedCore(
            stored = details.map { summary(it.id, it.title, it.price) },
            details = details.toList(),
        )

    @Test
    fun tappingAProductShowsItsDescriptionBrandAndStockStatus() {
        show(stocked(mascara))

        open("Essence Mascara")

        awaitText("Lengthens and volumises lashes")
        awaitText("Brand: Essence")
        awaitText("Low stock")
    }

    @Test
    fun aProductWithNoBrandShowsNoBrandLine() {
        show(stocked(mascara.copy(brand = null)))

        open("Essence Mascara")

        awaitText("Lengthens and volumises lashes")
        assertEquals(0, compose.onAllNodesWithText("Brand:", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun backReturnsToTheListWithTheTappedProductStillVisible() {
        val products = (1L..30L).map { mascara.copy(id = it, title = "Product $it") }
        show(stocked(*products.toTypedArray()), pageSize = 10)
        awaitText("Product 1")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Product 25"))
        compose.onNodeWithText("Product 25").performClick()
        awaitText("Lengthens and volumises lashes")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.waitForIdle()
        compose.onAllNodesWithText(mascara.description).assertCountEquals(0)
        compose.onNodeWithText("Product 25").assertIsDisplayed()
    }

    @Test
    fun anIdTheCoreDoesNotKnowShowsProductNotFound() {
        show(FakeSharedCore(stored = listOf(summary(id = 7, title = "Vanished Lipstick", price = 5.0))))

        open("Vanished Lipstick")

        awaitText("Product not found")
    }

    @Test
    fun aFailedReadShowsCouldNotLoadRatherThanProductNotFound() {
        val core = stocked(mascara)
        core.detailFailing = true
        show(core)

        open("Essence Mascara")

        awaitText("Couldn't load this product")
        assertEquals(0, compose.onAllNodesWithText("Product not found").fetchSemanticsNodes().size)
    }

    @Test
    fun retryingOnceStorageRecoversShowsTheProduct() {
        val core = stocked(mascara)
        core.detailFailing = true
        show(core)
        open("Essence Mascara")
        awaitText("Couldn't load this product")

        core.detailFailing = false
        compose.onNodeWithText("Retry").performClick()

        awaitText("Lengthens and volumises lashes")
        assertEquals(0, compose.onAllNodesWithText("Couldn't load this product").fetchSemanticsNodes().size)
    }

    @Test
    fun theDetailShowsCategoryPricesRatingAndTags() {
        show(stocked(mascara))

        open("Essence Mascara")

        awaitText("Category: beauty")
        awaitText("$9.00, was $10.00")
        awaitText("Rating: Excellent")
        awaitText("Tags: mascara, lashes")
    }

    @Test
    fun eachStockStatusHasItsOwnLabel() {
        compose.setContent {
            Column { StockStatus.entries.forEach { ProductDetailScreen(mascara.copy(stockStatus = it)) } }
        }

        val labels = listOf("In stock", "Low stock", "Out of stock")
        assertEquals(List(3) { 1 }, labels.map { compose.onAllNodesWithText(it).fetchSemanticsNodes().size })
    }

    private fun open(title: String) {
        awaitText(title)
        compose.onNodeWithText(title).performClick()
    }

    private fun awaitText(text: String) {
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
