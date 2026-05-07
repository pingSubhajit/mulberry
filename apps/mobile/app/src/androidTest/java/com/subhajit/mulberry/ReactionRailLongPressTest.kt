package com.subhajit.mulberry

import android.view.ViewConfiguration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subhajit.mulberry.core.ui.TestTags
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
class ReactionRailLongPressTest {
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
            entryPoint.canvasSnapshotRenderer().clearSnapshots()
            entryPoint.backgroundImageRepository().clearBackground()
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @Test
    fun reactionRailAppearsWhileFingerStillDown() {
        seedDemoSession()
        composeRule.onNodeWithTag(TestTags.HOME_OPEN_CANVAS_BUTTON).performClick()

        // Press and hold on the canvas.
        composeRule.onNodeWithTag(TestTags.DRAWING_CANVAS).performTouchInput {
            down(center)
        }

        // Wait for the long-press timer to elapse while the pointer is still down.
        Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 250L)
        composeRule.waitForIdle()

        assertTagExists(TestTags.REACTION_RAIL)

        // Release to avoid leaving an active pointer for subsequent tests.
        composeRule.onNodeWithTag(TestTags.DRAWING_CANVAS).performTouchInput {
            up()
        }
    }

    private fun assertTagExists(tag: String) {
        val nodes = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes(
            atLeastOneRootRequired = false
        )
        assertTrue("Expected tag '$tag' to exist", nodes.isNotEmpty())
    }

    private fun seedDemoSession() {
        assumeTrue(BuildConfig.ENABLE_DEBUG_MENU)

        val applicationContext = composeRule.activity.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            TestDependencies::class.java
        )

        runBlocking {
            entryPoint.sessionBootstrapRepository().seedDemoSession()
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TestDependencies {
        fun featureFlagProvider(): com.subhajit.mulberry.core.flags.FeatureFlagProvider
        fun sessionBootstrapRepository(): com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
        fun drawingRepository(): com.subhajit.mulberry.drawing.DrawingRepository
        fun canvasSnapshotRenderer(): com.subhajit.mulberry.wallpaper.CanvasSnapshotRenderer
        fun backgroundImageRepository(): com.subhajit.mulberry.wallpaper.BackgroundImageRepository
    }
}
