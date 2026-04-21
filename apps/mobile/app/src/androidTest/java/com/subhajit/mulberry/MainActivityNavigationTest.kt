package com.subhajit.mulberry

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subhajit.mulberry.core.flags.FeatureFlagProvider
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.wallpaper.BackgroundImageRepository
import com.subhajit.mulberry.wallpaper.CanvasSnapshotRenderer
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
            entryPoint.canvasSnapshotRenderer().clearSnapshots()
            entryPoint.backgroundImageRepository().clearBackground()
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @Test
    fun signedOutLaunchShowsAuthLanding() {
        assertTagExists(TestTags.AUTH_LANDING_SCREEN)
    }

    @Test
    fun settingsReachableFromHome() {
        seedDemoSession()

        assertTagExists(TestTags.HOME_SCREEN)
        composeRule.onNodeWithTag(TestTags.HOME_SETTINGS_BUTTON).performClick()
        assertTagExists(TestTags.SETTINGS_SCREEN)
    }

    @Test
    fun canvasScreenReachableFromHomeAndSupportsDrawing() {
        seedDemoSession()
        composeRule.onNodeWithTag(TestTags.HOME_OPEN_CANVAS_BUTTON).performClick()

        assertTagExists(TestTags.CANVAS_SCREEN)

        composeRule.onNodeWithTag(TestTags.DRAWING_CANVAS).performTouchInput {
            down(center)
            moveTo(Offset(center.x + 120f, center.y + 40f))
            up()
        }
    }

    @Test
    fun lockScreenReachableFromHome() {
        seedDemoSession()
        composeRule.onNodeWithTag(TestTags.HOME_OPEN_LOCKSCREEN_BUTTON).performClick()

        assertTagExists(TestTags.LOCKSCREEN_SCREEN)
        assertTagExists(TestTags.LOCKSCREEN_OPEN_WALLPAPER_BUTTON)
    }

    @Test
    fun devOnlyControlsHiddenInProd() {
        seedDemoSession()
        composeRule.onNodeWithTag(TestTags.HOME_SETTINGS_BUTTON).performClick()

        if (BuildConfig.ENABLE_DEBUG_MENU) {
            assertTagExists(TestTags.SETTINGS_DEVELOPER_SECTION)
        } else {
            assertTagDoesNotExist(TestTags.SETTINGS_DEVELOPER_SECTION)
        }
    }

    @Test
    fun stateSurvivesActivityRecreation() {
        seedSignedInOnboardingState()
        assertTagExists(TestTags.ONBOARDING_NAME_SCREEN)

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertTagExists(TestTags.ONBOARDING_NAME_SCREEN)
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

    private fun seedSignedInOnboardingState() {
        val applicationContext = composeRule.activity.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            TestDependencies::class.java
        )

        runBlocking {
            entryPoint.sessionBootstrapRepository().cacheSession(
                com.subhajit.mulberry.data.bootstrap.AppSession(
                    accessToken = "access",
                    refreshToken = "refresh",
                    userId = "user-1"
                )
            )
            entryPoint.sessionBootstrapRepository().cacheBootstrap(
                com.subhajit.mulberry.data.bootstrap.SessionBootstrapState(
                    authStatus = com.subhajit.mulberry.data.bootstrap.AuthStatus.SIGNED_IN,
                    hasCompletedOnboarding = false,
                    userId = "user-1"
                )
            )
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TestDependencies {
        fun sessionBootstrapRepository(): SessionBootstrapRepository
        fun featureFlagProvider(): FeatureFlagProvider
        fun drawingRepository(): DrawingRepository
        fun backgroundImageRepository(): BackgroundImageRepository
        fun canvasSnapshotRenderer(): CanvasSnapshotRenderer
    }
}
