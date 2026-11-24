package bench.uitest

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val context = InstrumentationRegistry.getInstrumentation().context
        val intent =
            context.packageManager.getLaunchIntentForPackage(APP)!!.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("fixtureRecords", RECORDS)
                putExtra("fixtureSeed", SEED)
            }
        context.startActivity(intent)
    }

    @Test
    fun theFirstPageOfFixtureProductsShows() {
        assertNotNull(firstTitle())
    }

    @Test
    fun scrollingToTheEndLoadsEveryPage() {
        firstTitle()
        assertEquals(RECORDS, titlesToTheEnd().size)
    }

    @Test
    fun pullingToRefreshKeepsTheListAndReloadsIt() {
        val first = firstTitle()
        swipe(fromY = device.displayHeight / 3, toY = device.displayHeight)
        assertEquals(first, visibleTitles().firstOrNull())
        assertEquals(RECORDS, titlesToTheEnd().size)
    }

    private fun firstTitle(): String = device.wait(Until.findObject(By.text(PRODUCT_TITLE)), TIMEOUT_MS).text

    private fun visibleTitles(): List<String> = device.findObjects(By.text(PRODUCT_TITLE)).map { it.text }

    // Each fixture page has its own titles, so the distinct titles seen count the products loaded.
    private fun titlesToTheEnd(): Set<String> {
        val seen = mutableSetOf<String>()
        var swipesWithNothingNew = 0
        while (swipesWithNothingNew < IDLE_SWIPES) {
            device.waitForIdle()
            val before = seen.size
            seen += visibleTitles()
            swipesWithNothingNew = if (seen.size == before) swipesWithNothingNew + 1 else 0
            swipe(fromY = device.displayHeight * 3 / 4, toY = device.displayHeight / 4)
        }
        return seen
    }

    private fun swipe(
        fromY: Int,
        toY: Int,
    ) = device.swipe(device.displayWidth / 2, fromY, device.displayWidth / 2, toY, STEPS)

    private companion object {
        const val APP = "bench.android"
        const val RECORDS = 100
        const val SEED = 7L
        const val TIMEOUT_MS = 10_000L
        const val STEPS = 20
        const val IDLE_SWIPES = 3
        val PRODUCT_TITLE: Pattern = Pattern.compile("Product \\d+")
    }
}
