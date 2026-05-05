package com.subhajit.mulberry.app.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.subhajit.mulberry.SendReactionShortcutActivity
import com.subhajit.mulberry.reactions.ReactionType

object ReactionShortcutPublisher {
    private const val SHORTCUT_ID = "send_reaction_dynamic"
    private const val ACTION_SEND_REACTION = "com.subhajit.mulberry.action.SEND_REACTION"

    fun publish(context: Context, reactionType: ReactionType) {
        val intent = Intent(context, SendReactionShortcutActivity::class.java).apply {
            action = ACTION_SEND_REACTION
        }

        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(reactionType.shortcutLabel)
            .setLongLabel(reactionType.shortcutLabel)
            .setIcon(IconCompat.createWithBitmap(renderEmojiIcon(reactionType.emoji)))
            .setIntent(intent)
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    private fun renderEmojiIcon(emoji: String): Bitmap {
        val sizePx = 128
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.72f
            typeface = Typeface.DEFAULT
        }
        val x = sizePx / 2f
        val y = sizePx / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(emoji, x, y, paint)
        return bitmap
    }
}

