package com.subhajit.mulberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
            delay(650)
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
    val scale = remember { Animatable(0.85f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(reactionType) {
        scale.snapTo(0.85f)
        alpha.snapTo(1f)
        scale.animateTo(
            targetValue = 1.0f,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
        )
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 480, delayMillis = 120)
        )
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = reactionType.emoji,
            fontSize = 72.sp,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
        )
    }
}
