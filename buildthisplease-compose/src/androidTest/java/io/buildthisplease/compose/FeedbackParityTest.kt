package io.buildthisplease.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.buildthisplease.core.MockBuildThisPleaseClient
import io.buildthisplease.core.MockBuildThisPleaseScenario
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class FeedbackParityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun createRequestIncludesIosFieldsAndLimits() {
        compose.setContent {
            MaterialTheme { BuildThisPleaseFeedback(client = MockBuildThisPleaseClient()) }
        }

        compose.onNodeWithContentDescription("New request").performClick()
        compose.onNodeWithText("Email (optional)").assertIsDisplayed()
        compose.onNodeWithText("0/100").assertIsDisplayed()
        compose.onNodeWithText("0/5000").assertIsDisplayed()
        compose.onNodeWithText("Submit request").assertIsDisplayed()
    }

    @Test
    fun primaryIconActionsMeetAndroidTouchTargetSize() {
        compose.setContent { MaterialTheme { BuildThisPleaseFeedback(client = MockBuildThisPleaseClient()) } }

        compose.onNodeWithContentDescription("New request").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun loadingStateIsAnnouncedToTalkBack() {
        compose.setContent {
            MaterialTheme {
                BuildThisPleaseFeedback(
                    client = MockBuildThisPleaseClient(scenario = MockBuildThisPleaseScenario.LOADING),
                    stateKey = "loading-test",
                )
            }
        }

        compose.onNodeWithContentDescription("Loading requests…").assertIsDisplayed()
    }

    @Test
    fun accessibilityFontScaleUsesSectionMenu() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                BuildThisPleaseFeedback(client = MockBuildThisPleaseClient(), stateKey = "large-font-test")
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Requests (3)").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Requests (3)").performClick()
        compose.onNodeWithText("Done (1)").assertIsDisplayed()
    }

    @Test
    fun ownMessageRequiresLongPressBeforeShowingEditAction() {
        compose.setContent { BuildThisPleaseFeedback(client = MockBuildThisPleaseClient(), stateKey = "edit-test") }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Filter requests by status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Filter requests by status").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("A filter for planned work would help.").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("A filter for planned work would help.").performClick()
        compose.onAllNodesWithText("Edit message").assertCountEquals(0)
        compose.onNodeWithText("A filter for planned work would help.").performTouchInput { longClick() }
        compose.onNodeWithText("Edit message").assertIsDisplayed()
    }
    @Test
    fun switchingClientDoesNotRetainThePreviousAccountRequests() {
        val first = MockBuildThisPleaseClient()
        val second = MockBuildThisPleaseClient(scenario = MockBuildThisPleaseScenario.EMPTY)
        val current = mutableStateOf(first)
        compose.setContent { BuildThisPleaseFeedback(client = current.value, stateKey = "account-switch") }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Filter requests by status").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { current.value = second }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Filter requests by status").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText("Filter requests by status").assertCountEquals(0)
    }

    @Test
    fun updatesExposeCompletionAndOptionalPreferences() {
        compose.setContent { BuildThisPleaseUpdates(MockBuildThisPleaseClient(), onBack = {}, onOpenTicket = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Implemented release notes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Implemented release notes").assertIsDisplayed()
        compose.onNodeWithText("Local notifications").assertIsDisplayed()
    }

    @Test
    fun votingRefreshesTheRequestFollowControl() {
        val client = MockBuildThisPleaseClient()
        compose.setContent { BuildThisPleaseFeedback(client = client, stateKey = "vote-follow") }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Filter requests by status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Filter requests by status").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Follow request").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Vote for this request")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Stop updates").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Stop updates").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Follow request").fetchSemanticsNodes().isNotEmpty() }
    }

}
