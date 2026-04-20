package com.subhajit.elaris

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subhajit.elaris.core.flags.FeatureFlagProvider
import com.subhajit.elaris.core.ui.TestTags
import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
import com.subhajit.elaris.drawing.DrawingRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetState() {
        val applicationContext = composeRule.activity.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            TestDependencies::class.java
        )

        runBlocking {
            entryPoint.sessionBootstrapRepository().reset()
            entryPoint.featureFlagProvider().clearOverrides()
            entryPoint.drawingRepository().resetAllDrawingState()
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @Test
    fun welcomeNavigatesToPairing() {
        assertTagExists(TestTags.WELCOME_SCREEN)

        composeRule.onNodeWithTag(TestTags.WELCOME_CONTINUE_BUTTON).performClick()

        assertTagExists(TestTags.PAIRING_SCREEN)
    }

    @Test
    fun settingsReachableFromHome() {
        assumeTrue(BuildConfig.ENABLE_DEBUG_MENU)

        composeRule.onNodeWithTag(TestTags.WELCOME_CONTINUE_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.PAIRING_DEMO_BUTTON).performClick()

        assertTagExists(TestTags.HOME_SCREEN)
        composeRule.onNodeWithTag(TestTags.HOME_SETTINGS_BUTTON).performClick()
        assertTagExists(TestTags.SETTINGS_SCREEN)
    }

    @Test
    fun blankStateVisibleOnEmptyCanvasAndHiddenAfterDrawing() {
        assumeTrue(BuildConfig.ENABLE_DEBUG_MENU)

        composeRule.onNodeWithTag(TestTags.WELCOME_CONTINUE_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.PAIRING_DEMO_BUTTON).performClick()

        assertTagExists(TestTags.DRAWING_BLANK_STATE)

        composeRule.onNodeWithTag(TestTags.DRAWING_CANVAS).performTouchInput {
            down(center)
            moveTo(Offset(center.x + 120f, center.y + 40f))
            up()
        }

        assertTagDoesNotExist(TestTags.DRAWING_BLANK_STATE)
    }

    @Test
    fun lockScreenPlaceholderReachableFromHome() {
        assumeTrue(BuildConfig.ENABLE_DEBUG_MENU)

        composeRule.onNodeWithTag(TestTags.WELCOME_CONTINUE_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.PAIRING_DEMO_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.HOME_LOCKSCREEN_TAB).performClick()

        assertTagExists(TestTags.LOCKSCREEN_PLACEHOLDER)
    }

    @Test
    fun devOnlyControlsHiddenInProd() {
        composeRule.onNodeWithTag(TestTags.WELCOME_CONTINUE_BUTTON).performClick()

        if (BuildConfig.ENABLE_DEBUG_MENU) {
            assertTagExists(TestTags.PAIRING_DEMO_BUTTON)
        } else {
            assertTagDoesNotExist(TestTags.PAIRING_DEMO_BUTTON)
        }
    }

    @Test
    fun stateSurvivesActivityRecreation() {
        composeRule.onNodeWithTag(TestTags.WELCOME_CONTINUE_BUTTON).performClick()
        assertTagExists(TestTags.PAIRING_SCREEN)

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertTagExists(TestTags.PAIRING_SCREEN)
    }

    private fun assertTagExists(tag: String) {
        val nodes = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes(
            atLeastOneRootRequired = false
        )
        assertTrue("Expected tag '$tag' to exist", nodes.isNotEmpty())
    }

    private fun assertTagDoesNotExist(tag: String) {
        val nodes = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes(
            atLeastOneRootRequired = false
        )
        assertTrue("Expected tag '$tag' to be absent", nodes.isEmpty())
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TestDependencies {
        fun sessionBootstrapRepository(): SessionBootstrapRepository
        fun featureFlagProvider(): FeatureFlagProvider
        fun drawingRepository(): DrawingRepository
    }
}
