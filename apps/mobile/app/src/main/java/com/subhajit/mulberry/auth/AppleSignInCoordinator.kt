package com.subhajit.mulberry.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.browser.customtabs.CustomTabsIntent
import com.subhajit.mulberry.core.config.AppConfig
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred

/** What Sign in with Apple hands the app, as the backend's `/auth/apple` takes it. */
data class AppleIdCredential(
    val identityToken: String,
    /** Lets the server revoke the person's Apple tokens if they delete their account. */
    val authorizationCode: String?,
    /** The raw nonce; the token carries its SHA-256. */
    val nonce: String,
    /** Apple shares the person's name only the first time they sign in to Mulberry. */
    val displayName: String?
)

sealed class AppleSignInException(message: String) : Exception(message) {
    /** The person closed Apple's page themselves: nothing went wrong. */
    class Cancelled : AppleSignInException("Sign in with Apple was cancelled")

    class Failed(reason: String) : AppleSignInException(reason)
}

/**
 * Android has no native Sign in with Apple, so Mulberry opens Apple's web page in a Custom Tab.
 * Apple posts the result to the backend, which sends it back to [AppleSignInCallbackActivity];
 * the state names this app and must match, and the identity token carries the SHA-256 of a fresh
 * nonce, so a token can't be replayed.
 */
@Singleton
class AppleSignInCoordinator @Inject constructor(
    private val appConfig: AppConfig
) {
    private class Pending(
        val state: String,
        val nonce: String,
        val result: CompletableDeferred<AppleIdCredential>
    ) {
        /** Set once the Custom Tab is open, so a return to Mulberry without a result is a cancel. */
        var awaitingReturn = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Pending? = null

    /** Whether this build can sign in with Apple, or uses a development token. */
    val usesDevelopmentToken: Boolean
        get() = appConfig.enableDebugMenu &&
            (appConfig.appleServicesId.isBlank() || appConfig.appleRedirectUri.isBlank())

    @MainThread
    suspend fun authorize(activity: Activity): AppleIdCredential {
        if (usesDevelopmentToken) {
            return AppleIdCredential(
                identityToken = "dev-apple:dev-android-apple:subhajit@elaris.dev",
                authorizationCode = null,
                nonce = "development",
                displayName = "Subhajit"
            )
        }
        if (appConfig.appleServicesId.isBlank() || appConfig.appleRedirectUri.isBlank()) {
            throw AppleSignInException.Failed("Sign in with Apple is not configured")
        }

        pending?.result?.completeExceptionally(AppleSignInException.Cancelled())
        val nonce = randomToken(32)
        val request = Pending(
            state = "${activity.packageName}:${randomToken(16)}",
            nonce = nonce,
            result = CompletableDeferred()
        )
        pending = request
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(activity, authorizationUri(request.state, nonce))
            request.awaitingReturn = true
        } catch (error: ActivityNotFoundException) {
            pending = null
            throw AppleSignInException.Failed("No browser can open Sign in with Apple")
        }
        return try {
            request.result.await()
        } finally {
            if (pending === request) pending = null
        }
    }

    /** Apple's result, as the backend sent it back. A result for another request is ignored. */
    @MainThread
    fun onCallback(uri: Uri) {
        val request = pending ?: return
        if (uri.getQueryParameter("state") != request.state) return
        val error = uri.getQueryParameter("error")
        val identityToken = uri.getQueryParameter("id_token")
        when {
            error == APPLE_CANCELLED_ERROR -> request.result.completeExceptionally(AppleSignInException.Cancelled())
            error != null -> request.result.completeExceptionally(AppleSignInException.Failed(error))
            identityToken.isNullOrBlank() ->
                request.result.completeExceptionally(AppleSignInException.Failed("Apple returned no identity token"))
            else -> request.result.complete(
                AppleIdCredential(
                    identityToken = identityToken,
                    authorizationCode = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() },
                    nonce = request.nonce,
                    displayName = uri.getQueryParameter("name")?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
    }

    /**
     * Mulberry came back to the front. The callback arrives before this when Apple finished, so a
     * request still waiting means the person closed the Custom Tab.
     */
    @MainThread
    fun onHostResumed() {
        val request = pending ?: return
        if (!request.awaitingReturn) return
        handler.postDelayed({
            if (pending === request && !request.result.isCompleted) {
                request.result.completeExceptionally(AppleSignInException.Cancelled())
            }
        }, RETURN_GRACE_MS)
    }

    private fun authorizationUri(state: String, nonce: String): Uri =
        Uri.parse(APPLE_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", appConfig.appleServicesId)
            .appendQueryParameter("redirect_uri", appConfig.appleRedirectUri)
            .appendQueryParameter("response_type", "code id_token")
            // Apple returns the name and email only by form post.
            .appendQueryParameter("response_mode", "form_post")
            .appendQueryParameter("scope", "name email")
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", sha256Hex(nonce))
            .build()

    private companion object {
        const val APPLE_AUTHORIZE_URL = "https://appleid.apple.com/auth/authorize"
        const val APPLE_CANCELLED_ERROR = "user_cancelled_authorize"
        const val RETURN_GRACE_MS = 600L

        val random = SecureRandom()

        fun randomToken(bytes: Int): String {
            val data = ByteArray(bytes).also(random::nextBytes)
            return android.util.Base64.encodeToString(
                data,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
        }

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
