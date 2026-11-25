package bench.uitest

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import bench.fixtures.ProductFixtures
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class ProductListDeviceTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun launchInFixtureMode() {
        device.executeShellCommand("pm clear $APP")
        // Named outright: since Android 11 the test package can't look the app up without a <queries> entry.
        val intent =
            Intent()
                .setClassName(APP, "$APP.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("fixtureRecords", RECORDS)
                .putExtra("fixtureSeed", SEED)
        InstrumentationRegistry.getInstrumentation().context.startActivity(intent)
    }

    @Test
    fun theFirstPageOfFixtureProductsShows() {
        assertNotNull(firstTitle())
    }

    @Test
    fun scrollingToTheEndLoadsEveryPage() {
        firstTitle()
        assertTrue("product $RECORDS never showed", scrollUntilShown(lastTitle()))
    }

    @Test
    fun pullingToRefreshKeepsTheListAndReloadsIt() {
        val first = firstTitle()
        swipe(fromY = device.displayHeight / 3, toY = device.displayHeight)
        assertTrue("the list emptied on refresh", device.wait(Until.hasObject(By.text(first)), TIMEOUT_MS))
        assertTrue("product $RECORDS never showed", scrollUntilShown(lastTitle()))
    }

    private fun firstTitle(): String = device.wait(Until.findObject(By.text(PRODUCT_TITLE)), TIMEOUT_MS).text

    // The last product's title, from the same fixture page the app's "network" serves.
    private fun lastTitle(): String {
        val page = ProductFixtures.page(PAGE_SIZE, SEED, skip = RECORDS - PAGE_SIZE, total = RECORDS)
        return TITLE_IN_JSON
            .toRegex()
            .findAll(page.decodeToString())
            .last()
            .groupValues[1]
    }

    private fun scrollUntilShown(title: String): Boolean =
        (1..MAX_SWIPES).any {
            device.waitForIdle()
            device.hasObject(By.text(title)).also { shown ->
                if (!shown) swipe(fromY = device.displayHeight * 3 / 4, toY = device.displayHeight / 4)
            }
        }

    private fun swipe(
        fromY: Int,
        toY: Int,
    ) = device.swipe(device.displayWidth / 2, fromY, device.displayWidth / 2, toY, STEPS)

    private companion object {
        const val APP = "bench.android"
        const val RECORDS = 100
        const val PAGE_SIZE = 20
        const val SEED = 7L
        const val TIMEOUT_MS = 10_000L
        const val STEPS = 20
        const val MAX_SWIPES = 60
        const val TITLE_IN_JSON = "\"title\":\"(Product \\d+)\""
        val PRODUCT_TITLE: Pattern = Pattern.compile("Product \\d+")
    }
}
