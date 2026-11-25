package bench.macrobenchmark

import android.content.Intent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

const val APP_PACKAGE = "bench.android"

// Fixture titles are "Product <n>"; the list's "Products" header doesn't match.
private val PRODUCT_TITLE = Pattern.compile("Product \\d+")
private const val FIRST_PAGE_TIMEOUT_MS = 30_000L

/** Starts the app in fixture mode, so its "network" serves [records] generated products. */
fun fixtureIntent(records: Int): Intent =
    Intent()
        .setClassName(APP_PACKAGE, "$APP_PACKAGE.MainActivity")
        .putExtra("fixtureRecords", records)

fun UiDevice.waitForFirstProduct() {
    check(wait(Until.hasObject(By.text(PRODUCT_TITLE)), FIRST_PAGE_TIMEOUT_MS)) {
        "no product rendered within $FIRST_PAGE_TIMEOUT_MS ms"
    }
}

/** Products the benchmarks' fixture serves: 50 pages of 20. */
const val RECORDS = 1000
const val ITERATIONS = 5
