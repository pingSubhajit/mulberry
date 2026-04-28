package com.subhajit.mulberry.pairing.inbound

import android.content.Context
import android.net.Uri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.subhajit.mulberry.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class InstallReferrerInboundInviteIngester @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val inboundInviteRepository: InboundInviteRepository
) {
    private val mutex = Mutex()

    fun ingestIfNeededAsync() {
        applicationScope.launch {
            ingestIfNeeded()
        }
    }

    suspend fun ingestIfNeeded() {
        mutex.withLock {
            if (inboundInviteRepository.wasInstallReferrerChecked()) return
            val referrerResult = runCatching { fetchInstallReferrerString() }
            val failure = referrerResult.exceptionOrNull()
            if (failure is InstallReferrerUnavailableException && failure.isPermanent) {
                inboundInviteRepository.markInstallReferrerChecked()
            }

            val referrer = referrerResult.getOrNull()
            if (referrer != null) {
                // Mark as checked when we successfully fetched (even if empty).
                inboundInviteRepository.markInstallReferrerChecked()
            }
            val code = extractInviteCodeFromReferrer(referrer)
            if (code != null) {
                inboundInviteRepository.setPendingInvite(code, InboundInviteSource.InstallReferrer)
                InboundInviteActionController.notifyInviteReceived()
            }
        }
    }

    private fun extractInviteCodeFromReferrer(referrer: String?): String? {
        val raw = referrer?.takeIf(String::isNotBlank) ?: return null
        val uri = Uri.parse("https://mulberry.my/?$raw")
        return normalizeInviteCode(uri.getQueryParameter("invite_code"))
    }

    private suspend fun fetchInstallReferrerString(): String? {
        val client = InstallReferrerClient.newBuilder(context).build()
        return try {
            val details = client.startConnectionAwaitOrThrow()
            details.installReferrer
        } finally {
            runCatching { client.endConnection() }
        }
    }
}

private class InstallReferrerUnavailableException(
    val responseCode: Int,
    val isPermanent: Boolean,
    message: String
) : IllegalStateException(message)

private suspend fun InstallReferrerClient.startConnectionAwaitOrThrow(): ReferrerDetails =
    suspendCancellableCoroutine { continuation ->
        startConnection(
            object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (!continuation.isActive) return
                    if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) {
                        val isPermanent =
                            responseCode == InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED ||
                                responseCode == InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR
                        continuation.resumeWithException(
                            InstallReferrerUnavailableException(
                                responseCode = responseCode,
                                isPermanent = isPermanent,
                                message = "Install referrer unavailable responseCode=$responseCode"
                            )
                        )
                        return
                    }
                    try {
                        continuation.resume(installReferrer)
                    } catch (error: Throwable) {
                        continuation.resumeWithException(error)
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(
                        CancellationException("Install referrer service disconnected")
                    )
                }
            }
        )
    }
