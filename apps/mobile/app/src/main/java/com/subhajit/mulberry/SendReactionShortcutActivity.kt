package com.subhajit.mulberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.subhajit.mulberry.app.shortcut.ReactionShortcutPublisher
import com.subhajit.mulberry.reactions.ReactionLocalStore
import com.subhajit.mulberry.reactions.ReactionRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
            ReactionShortcutPublisher.publish(this@SendReactionShortcutActivity, reactionType)
            withContext(Dispatchers.IO) {
                reactionRepository.sendReaction(reactionType)
            }
            finish()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
