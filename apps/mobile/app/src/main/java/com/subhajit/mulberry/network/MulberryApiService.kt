package com.subhajit.mulberry.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Path
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response

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

    @GET("/streak")
    suspend fun getStreak(@Query("today") today: String): StreakResponse

    @PUT("/me/profile")
    suspend fun updateProfile(@Body request: ProfileRequest): BootstrapResponse

    @PUT("/me/display-name")
    suspend fun updateDisplayName(@Body request: DisplayNameRequest): BootstrapResponse

    @Multipart
    @PUT("/me/profile-photo")
    suspend fun updateProfilePhoto(@Part image: MultipartBody.Part): BootstrapResponse

    @PUT("/me/partner-profile")
    suspend fun updatePartnerProfile(@Body request: PartnerProfileRequest): BootstrapResponse

    @Multipart
    @PUT("/me/partner-profile-with-photo")
    suspend fun updatePartnerProfileWithPhoto(
        @Part("partnerDisplayName") partnerDisplayName: RequestBody,
        @Part("anniversaryDate") anniversaryDate: RequestBody,
        @Part image: MultipartBody.Part
    ): BootstrapResponse

    @Multipart
    @PUT("/me/partner-profile-photo")
    suspend fun updatePartnerProfilePhoto(@Part image: MultipartBody.Part): BootstrapResponse

    @PUT("/me/canvas-stroke-render-mode")
    suspend fun updateCanvasStrokeRenderMode(@Body request: UpdateCanvasStrokeRenderModeRequest): BootstrapResponse

    @PUT("/me/wallpaper-status")
    suspend fun updateWallpaperStatus(@Body request: UpdateWallpaperStatusRequest): DebugActionResponse

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

    @POST("/debug/pairing-disconnected-push")
    suspend fun sendDebugPairingDisconnectedPush(): DebugActionResponse

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

    @GET("/stickers/packs")
    suspend fun getStickerPacks(): StickerPackListResponse

    @GET("/stickers/packs/{packKey}")
    suspend fun getStickerPackDetail(
        @Path("packKey") packKey: String,
        @Query("version") version: Int?
    ): StickerPackDetailResponse

    @GET("/stickers/assets/url")
    suspend fun getStickerAssetUrl(
        @Query("packKey") packKey: String,
        @Query("version") version: Int,
        @Query("stickerId") stickerId: String,
        @Query("variant") variant: String
    ): StickerAssetUrlResponse

    @POST("/reactions/send")
    suspend fun sendReaction(@Body request: SendReactionRequest): ReactionSendResponse

    @POST("/reactions/lease")
    suspend fun leaseReactionPlayback(@Body request: ReactionLeaseRequest): ReactionLeaseResponse

    @POST("/reactions/confirm")
    suspend fun confirmReactionPlayback(@Body request: ReactionConfirmRequest): ReactionConfirmResponse

    @GET("/whats-new/{version}.md")
    suspend fun getWhatsNewMarkdown(@Path("version") version: String): Response<ResponseBody>

    @GET("/whats-new/latest.md")
    suspend fun getLatestWhatsNewMarkdown(): Response<ResponseBody>
}
