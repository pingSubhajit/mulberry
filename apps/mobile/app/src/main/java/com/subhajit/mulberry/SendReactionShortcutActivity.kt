package com.subhajit.mulberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.subhajit.mulberry.app.shortcut.ReactionShortcutPublisher
import com.subhajit.mulberry.reactions.ReactionLocalStore
import com.subhajit.mulberry.reactions.ReactionRepository
import com.subhajit.mulberry.reactions.ReactionType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

@AndroidEntryPoint
class SendReactionShortcutActivity : ComponentActivity() {

    @Inject lateinit var reactionRepository: ReactionRepository
    @Inject lateinit var reactionLocalStore: ReactionLocalStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        lifecycleScope.launch {
            val reactionType = withContext(Dispatchers.IO) { reactionLocalStore.getLastUsedReaction() }
            setContent {
                ReactionShortcutOverlay(reactionType)
            }
            ReactionShortcutPublisher.publish(this@SendReactionShortcutActivity, reactionType)
            launch(Dispatchers.IO) {
                reactionRepository.sendReaction(reactionType)
            }
            delay(900)
            finish()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}

@Composable
private fun ReactionShortcutOverlay(reactionType: ReactionType) {
    val scale = remember { Animatable(0.95f) }
    val alpha = remember { Animatable(0f) }
    val offsetYDp = remember { Animatable(12f) }
    val offsetXDp = remember { Animatable(0f) }
    val rotationZAnim = remember { Animatable(0f) }

    LaunchedEffect(reactionType) {
        scale.snapTo(0.95f)
        alpha.snapTo(0f)
        offsetYDp.snapTo(12f)
        offsetXDp.snapTo(0f)
        rotationZAnim.snapTo(0f)

        val slideMs = 150f
        val holdMs = 240f
        val fallMs = 240f
        val startAtMs = android.os.SystemClock.uptimeMillis()
        val wiggleHz = 3.6f

        val wiggleJob = launch {
            while (true) {
                val tMs = (android.os.SystemClock.uptimeMillis() - startAtMs).toFloat().coerceAtLeast(0f)
                val slideRamp = (tMs / slideMs).coerceIn(0f, 1f)
                val fallProgress = ((tMs - slideMs - holdMs) / fallMs).coerceIn(0f, 1f)
                val fallDamp =
                    if (tMs < slideMs + holdMs) 1f
                    else (1f - fallProgress * 0.8f).coerceIn(0f, 1f)
                val amplitudeDeg = 4.5f * slideRamp * fallDamp
                val tSec = tMs / 1000f
                rotationZAnim.snapTo(sin(2f * PI.toFloat() * wiggleHz * tSec) * amplitudeDeg)
                delay(16)
            }
        }

        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing)
            )
        }
        launch {
            scale.animateTo(
                targetValue = 1.06f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
        // Use deterministic timing so we don't "miss" the fall before the activity finishes.
        offsetYDp.animateTo(
            targetValue = -10f,
            animationSpec = tween(durationMillis = slideMs.toInt(), easing = FastOutSlowInEasing)
        )

        delay(holdMs.toLong())

        launch {
            offsetXDp.animateTo(
                targetValue = 7f,
                animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)
            )
            offsetXDp.animateTo(
                targetValue = -4f,
                animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)
            )
            offsetXDp.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing)
            )
        }
        launch {
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
        }
        launch {
            scale.animateTo(
                targetValue = 0.96f,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
        }
        offsetYDp.animateTo(
            targetValue = 18f,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
        )
        wiggleJob.cancel()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = reactionType.emoji,
            fontSize = 92.sp,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
                translationY = offsetYDp.value.dp.toPx()
                translationX = offsetXDp.value.dp.toPx()
                rotationZ = rotationZAnim.value
            }
        )
    }
}
