package com.subhajit.mulberry.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Path

interface MulberryApiService {
    @POST("/auth/google")
    suspend fun authenticateWithGoogle(@Body request: GoogleAuthRequest): AuthResponse

    @POST("/auth/refresh")
    suspend fun refreshSession(@Body request: RefreshRequest): AuthResponse

    @POST("/auth/logout")
    suspend fun logout()

    @POST("/devices/fcm-token")
    suspend fun registerFcmToken(@Body request: RegisterFcmTokenRequest): DeviceTokenResponse

    @HTTP(method = "DELETE", path = "/devices/fcm-token", hasBody = true)
    suspend fun unregisterFcmToken(@Body request: UnregisterFcmTokenRequest)

    @GET("/bootstrap")
    suspend fun getBootstrap(): BootstrapResponse

    @PUT("/me/profile")
    suspend fun updateProfile(@Body request: ProfileRequest): BootstrapResponse

    @PUT("/me/display-name")
    suspend fun updateDisplayName(@Body request: DisplayNameRequest): BootstrapResponse

    @PUT("/me/partner-profile")
    suspend fun updatePartnerProfile(@Body request: PartnerProfileRequest): BootstrapResponse

    @POST("/invites")
    suspend fun createInvite(): CreateInviteResponse

    @POST("/invites/redeem")
    suspend fun redeemInvite(@Body request: RedeemInviteRequest): RedeemInviteResponse

    @POST("/invites/{inviteId}/accept")
    suspend fun acceptInvite(@Path("inviteId") inviteId: String): AcceptInviteResponse

    @POST("/invites/{inviteId}/decline")
    suspend fun declineInvite(@Path("inviteId") inviteId: String): BootstrapResponse

    @POST("/pairing/disconnect")
    suspend fun disconnectPairing(): BootstrapResponse

    @POST("/debug/pairing-confirmation-push")
    suspend fun sendDebugPairingConfirmationPush(): DebugActionResponse

    @GET("/canvas/ops")
    suspend fun getCanvasOperations(@Query("afterRevision") afterRevision: Long): CanvasOpsResponse

    @POST("/canvas/ops/batch")
    suspend fun postCanvasOperationBatch(@Body request: CanvasOperationBatchRequest): CanvasOpsResponse

    @GET("/canvas/snapshot")
    suspend fun getCanvasSnapshot(): CanvasSnapshotResponse

    @GET("/wallpapers")
    suspend fun getWallpapers(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int
    ): WallpaperCatalogResponse
}
