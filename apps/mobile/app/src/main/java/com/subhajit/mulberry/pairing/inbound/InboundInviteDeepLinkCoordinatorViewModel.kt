package com.subhajit.mulberry.pairing.inbound

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.pairing.InviteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface InboundInviteDeepLinkEffect {
    data object NavigateToBootstrap : InboundInviteDeepLinkEffect
}

@HiltViewModel
class InboundInviteDeepLinkCoordinatorViewModel @Inject constructor(
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val inboundInviteRepository: InboundInviteRepository,
    private val installReferrerInboundInviteIngester: InstallReferrerInboundInviteIngester,
    private val inviteRepository: InviteRepository
) : ViewModel() {
    private val _effects = MutableSharedFlow<InboundInviteDeepLinkEffect>()
    val effects = _effects.asSharedFlow()

    private var lastRedeemAttemptCode: String? = null

    init {
        viewModelScope.launch {
            combine(
                sessionBootstrapRepository.state,
                inboundInviteRepository.pendingInvite
            ) { bootstrap, inbound ->
                bootstrap to inbound
            }.collect { (bootstrap, inbound) ->
                if (bootstrap.authStatus != AuthStatus.SIGNED_IN) return@collect
                if (bootstrap.hasCompletedOnboarding) return@collect

                // Ensure deferred install referrer has had a chance to populate the pending invite.
                installReferrerInboundInviteIngester.ingestIfNeeded()

                val pending = inbound ?: inboundInviteRepository.pendingInvite.first() ?: return@collect
                val now = System.currentTimeMillis()
                if (now - pending.receivedAtMs > INBOUND_INVITE_TTL_MS) {
                    inboundInviteRepository.clearPendingInvite()
                    lastRedeemAttemptCode = null
                    return@collect
                }
                if (pending.dismissedAtMs != null) return@collect

                when (bootstrap.pairingStatus) {
                    PairingStatus.INVITE_PENDING_ACCEPTANCE -> {
                        if (lastRedeemAttemptCode == pending.code) return@collect
                        lastRedeemAttemptCode = pending.code
                        inboundInviteRepository.clearPendingInvite()
                        _effects.emit(InboundInviteDeepLinkEffect.NavigateToBootstrap)
                    }

                    PairingStatus.UNPAIRED -> {
                        if (lastRedeemAttemptCode == pending.code) return@collect
                        lastRedeemAttemptCode = pending.code
                        val redemption = inviteRepository.redeemInvite(pending.code)
                        if (redemption.isSuccess) {
                            inboundInviteRepository.clearPendingInvite()
                            _effects.emit(InboundInviteDeepLinkEffect.NavigateToBootstrap)
                        }
                        // On failure, keep the pending invite so the user can continue manually via "Enter code".
                    }

                    PairingStatus.PAIRED -> Unit
                }
            }
        }
    }

    private companion object {
        const val INBOUND_INVITE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
