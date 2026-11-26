package bench.android

import androidx.compose.ui.test.junit4.ComposeTestRule

// Compose's default 1 s flakes on shared CI runners while paging loads on a background dispatcher (#257).
private const val CI_TIMEOUT_MS = 5_000L

/** `waitUntil` with a timeout that holds on CI; a condition that never comes true still fails, only later. */
fun ComposeTestRule.waitFor(condition: () -> Boolean) = waitUntil(CI_TIMEOUT_MS, condition)
