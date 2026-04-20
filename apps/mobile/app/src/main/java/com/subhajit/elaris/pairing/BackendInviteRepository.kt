package com.subhajit.elaris.pairing

import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
import com.subhajit.elaris.network.ElarisApiService
import com.subhajit.elaris.network.RedeemInviteRequest
import com.subhajit.elaris.network.toDomainBootstrap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

@Singleton
class BackendInviteRepository @Inject constructor(
    private val apiService: ElarisApiService,
    private val sessionBootstrapRepository: SessionBootstrapRepository
) : InviteRepository {
    private val currentInviteState = MutableStateFlow<CreateInviteResult?>(null)

    override val currentInvite: Flow<CreateInviteResult?> = currentInviteState

    override suspend fun createInvite(): Result<CreateInviteResult> = runCatching {
        val response = apiService.createInvite()
        CreateInviteResult(
            inviteId = response.inviteId,
            code = response.code,
            expiresAt = response.expiresAt
        ).also { currentInviteState.value = it }
    }

    override suspend fun redeemInvite(code: String): Result<InviteRedemptionResult> = runCatching {
        val response = apiService.redeemInvite(RedeemInviteRequest(code = code))
        sessionBootstrapRepository.cacheBootstrap(response.bootstrapState.toDomainBootstrap())
        InviteRedemptionResult(
            inviteId = response.inviteId,
            inviterDisplayName = response.inviterDisplayName,
            recipientDisplayName = response.recipientDisplayName,
            code = response.code,
            status = response.status
        )
    }

    override suspend fun acceptInvite(inviteId: String): Result<Unit> = runCatching {
        val response = apiService.acceptInvite(inviteId)
        sessionBootstrapRepository.cacheBootstrap(response.bootstrapState.toDomainBootstrap())
    }

    override suspend fun declineInvite(inviteId: String): Result<Unit> = runCatching {
        val response = apiService.declineInvite(inviteId)
        sessionBootstrapRepository.cacheBootstrap(response.toDomainBootstrap())
    }
}
