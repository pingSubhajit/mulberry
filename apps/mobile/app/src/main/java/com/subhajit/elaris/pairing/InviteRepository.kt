package com.subhajit.elaris.pairing

import kotlinx.coroutines.flow.Flow

data class CreateInviteResult(
    val inviteId: String,
    val code: String,
    val expiresAt: String
)

data class InviteRedemptionResult(
    val inviteId: String,
    val inviterDisplayName: String,
    val recipientDisplayName: String,
    val code: String,
    val status: String
)

interface InviteRepository {
    val currentInvite: Flow<CreateInviteResult?>

    suspend fun createInvite(): Result<CreateInviteResult>

    suspend fun redeemInvite(code: String): Result<InviteRedemptionResult>

    suspend fun acceptInvite(inviteId: String): Result<Unit>

    suspend fun declineInvite(inviteId: String): Result<Unit>
}
