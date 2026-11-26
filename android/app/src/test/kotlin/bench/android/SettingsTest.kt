package bench.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bench.android.core.FakeSharedCore
import bench.android.core.FetchRequest
import bench.android.core.summary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DrainMainLooper()).around(compose)

    private val settings = Settings(RuntimeEnvironment.getApplication())
    private val embedded = CoreChoice(CoreKind.Kmp, Storage.Embedded)
    private val chatty = CoreChoice(CoreKind.Kmp, Storage.Chatty)
    private val mascara = summary(id = 1, title = "Essence Mascara", price = 9.99)

    private fun noNetwork(request: FetchRequest): FetchResult = error("unexpected fetch of ${request.url}")

    private fun show(cores: CoreFactory = CoreFactory { _, _ -> FakeSharedCore() }) {
        val session = CoreSession(settings, cores)
        compose.setContent { BenchApp(session, ::noNetwork) }
    }

    private fun awaitText(text: String) =
        compose.waitFor {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun theSettingsScreenShowsTheCurrentCoreAndStorageVariant() {
        show()

        compose.onNodeWithText("Settings").performClick()

        awaitText("Core: KMP")
        awaitText("Storage: Embedded")
    }

    @Test
    fun choosingDelegatedChattyReopensTheCoreWithThatVariantAndTheListStillLoads() {
        val opened = mutableListOf<Pair<CoreChoice, Boolean>>()
        show { choice, empty ->
            opened += choice to empty
            FakeSharedCore(stored = if (empty) listOf(mascara) else emptyList())
        }

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Delegated, chatty").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        awaitText("Essence Mascara")
        assertEquals(
            listOf(embedded to false, chatty to true),
            opened,
        )
    }

    @Test
    fun theChoiceSurvivesAnAppRestart() {
        show()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Delegated, chatty").performClick()

        val opened = mutableListOf<Pair<CoreChoice, Boolean>>()
        CoreSession(Settings(RuntimeEnvironment.getApplication())) { choice, empty ->
            opened += choice to empty
            FakeSharedCore()
        }

        assertEquals(listOf(chatty to false), opened)
    }
}
