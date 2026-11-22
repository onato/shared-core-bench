package bench.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchingTheAppShowsTheProductsTitle() {
        compose.onNodeWithText("Products").assertIsDisplayed()
    }
}
