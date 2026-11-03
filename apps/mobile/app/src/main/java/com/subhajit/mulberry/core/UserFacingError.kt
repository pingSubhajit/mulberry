package com.subhajit.mulberry.core

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONObject
import retrofit2.HttpException

/**
 * What to tell people when something fails, matching the iPhone and the Mac. The server sends a
 * machine-readable `code` with each failure people can see; the words for it live here, never in
 * the server's reply, so people never read raw server or system text.
 */
object UserFacingError {
    const val OFFLINE = "Can’t reach Mulberry. Check your connection and try again."
    private const val SERVER_TROUBLE = "Mulberry is having trouble right now. Try again in a moment."

    /**
     * What to say about [error], or null when there is nothing to say, as for a cancelled request.
     * [fallback] names what failed, as "Couldn’t send your reaction. Try again."
     */
    fun message(error: Throwable, fallback: String): String? = when (error) {
        is CancellationException -> null
        is IOException -> OFFLINE
        is HttpException -> error.reasonCode()?.let(::messageForCode)
            ?: if (error.code() >= 500) SERVER_TROUBLE else fallback
        else -> fallback
    }

    /** The server's machine-readable reason, as "ALREADY_PAIRED". */
    fun code(error: Throwable): String? = (error as? HttpException)?.reasonCode()

    /** The words for a server failure [code], or null when Mulberry has none. */
    fun messageForCode(code: String): String? = when (code) {
        "OWN_INVITE" -> "That’s your own invite code. Share it with your partner."
        "INVITE_EXPIRED", "INVITE_NO_LONGER_VALID" -> "That invite code has expired. Ask your partner for a new one."
        "INVITE_NOT_FOUND" -> "That invite code wasn’t found. Check it and try again."
        "INVITE_CODE_REQUIRED" -> "Enter the 6-digit code your partner sent you."
        "ALREADY_PAIRED" -> "You’re already paired. Unpair in Settings to join a new invite."
        "NOT_PAIRED" -> "You’re not paired yet. Invite your partner to start."
        "ONBOARDING_INCOMPLETE" -> "Add your partner’s name and your anniversary, then create an invite."
        "PROFILE_PHOTO_TOO_LARGE" -> "That photo is too large. Choose one under 10 MB."
        "PROFILE_PHOTO_UNSUPPORTED" -> "Choose a JPEG, PNG or WebP photo."
        "PROFILE_PHOTO_UNREADABLE" -> "That photo couldn’t be opened. Choose another one."
        "TOO_MANY_INVITE_ATTEMPTS" -> "Too many tries. Wait an hour, then enter the code again."
        "TOO_MANY_REACTIONS" -> "That’s a lot of love. Wait a moment, then send more."
        else -> null
    }

    /** The error body can be read once, so the code is kept on the exception after the first read. */
    private fun HttpException.reasonCode(): String? = synchronized(codes) {
        codes.getOrPut(this) {
            runCatching {
                response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }
                    ?.let { JSONObject(it).optString("code") }
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull().orEmpty()
        }.takeIf { it.isNotEmpty() }
    }

    private val codes = java.util.WeakHashMap<HttpException, String>()
}
