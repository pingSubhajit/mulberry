package com.subhajit.mulberry.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface MulberryApiService {
    @POST("/auth/google")
    suspend fun authenticateWithGoogle(@Body request: GoogleAuthRequest): AuthResponse

    @POST("/auth/refresh")
    suspend fun refreshSession(@Body request: RefreshRequest): AuthResponse

    @POST("/auth/logout")
    suspend fun logout()

    @GET("/bootstrap")
    suspend fun getBootstrap(): BootstrapResponse

    @PUT("/me/profile")
    suspend fun updateProfile(@Body request: ProfileRequest): BootstrapResponse

    @POST("/invites")
    suspend fun createInvite(): CreateInviteResponse

    @POST("/invites/redeem")
    suspend fun redeemInvite(@Body request: RedeemInviteRequest): RedeemInviteResponse

    @POST("/invites/{inviteId}/accept")
    suspend fun acceptInvite(@Path("inviteId") inviteId: String): AcceptInviteResponse

    @POST("/invites/{inviteId}/decline")
    suspend fun declineInvite(@Path("inviteId") inviteId: String): BootstrapResponse
}
