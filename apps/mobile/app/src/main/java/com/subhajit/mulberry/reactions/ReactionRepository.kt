package com.subhajit.mulberry.reactions

import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.ReactionConfirmRequest
import com.subhajit.mulberry.network.ReactionLeaseRequest
import com.subhajit.mulberry.network.ReactionSendResponse
import com.subhajit.mulberry.network.SendReactionRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReactionRepository @Inject constructor(
    private val apiService: MulberryApiService
) {
    suspend fun sendReaction(type: ReactionType): Result<ReactionSendResponse> = runCatching {
        apiService.sendReaction(SendReactionRequest(reactionType = type.apiValue))
    }

    suspend fun leasePlayback(generation: Long, deviceId: String): Result<String> = runCatching {
        apiService.leaseReactionPlayback(
            ReactionLeaseRequest(generation = generation, deviceId = deviceId)
        ).status
    }

    suspend fun confirmPlayed(generation: Long, deviceId: String): Result<Unit> = runCatching {
        apiService.confirmReactionPlayback(
            ReactionConfirmRequest(generation = generation, deviceId = deviceId)
        )
        Unit
    }
}
