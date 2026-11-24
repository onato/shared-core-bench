package bench.android

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.shadows.ShadowLooper

/**
 * Runs what the main looper still holds once the inner rules have torn down.
 *
 * Compose's `AndroidUiDispatcher.Main` is one per process and remembers that it has posted to the main looper.
 * Robolectric clears the looper between tests, so a post still queued when a test ends (a paging update cancelled
 * by disposal, say) is dropped, the dispatcher never posts again, and every later test in the JVM stalls.
 */
class DrainMainLooper : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ) = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } finally {
                ShadowLooper.idleMainLooper()
            }
        }
    }
}
