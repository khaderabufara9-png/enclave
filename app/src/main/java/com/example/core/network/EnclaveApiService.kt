package com.example.core.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface EnclaveApiService {
    @GET("/api/v1/health")
    suspend fun healthCheck(): Response<HealthResponse>

    @POST("/api/v1/devices/register")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): Response<RegisterDeviceResponse>

    @POST("/api/v1/invitations")
    suspend fun publishInvitation(
        @Header("X-Enclave-Device-Id") deviceId: String,
        @Body request: PublishInvitationRequest,
    ): Response<Map<String, Any>>

    @GET("/api/v1/invitations/{code}")
    suspend fun lookupInvitation(
        @Path("code") code: String,
        @Header("X-Enclave-Device-Id") deviceId: String,
    ): Response<LookupInvitationResponse>

    @POST("/api/v1/invitations/{code}/accept")
    suspend fun acceptInvitation(
        @Path("code") code: String,
        @Body request: AcceptInvitationRequest,
    ): Response<Map<String, Any>>

    @POST("/api/v1/messages/relay")
    suspend fun sendEncryptedRelay(
        @Header("X-Enclave-Device-Id") deviceId: String,
        @Body request: RelayMessageRequest,
    ): Response<RelayMessageResponse>

    @GET("/api/v1/messages/pending/{deviceId}")
    suspend fun fetchPendingEnvelopes(
        @Path("deviceId") deviceId: String,
        @Header("X-Enclave-Timestamp") timestampMs: Long,
        @Header("X-Enclave-Signature") signatureB64: String,
    ): Response<PendingEnvelopesResponse>

    @POST("/api/v1/messages/ack")
    suspend fun acknowledgeEnvelope(@Body request: AckEnvelopeRequest): Response<Map<String, Any>>

    @POST("/api/v1/deletions")
    suspend fun sendDeletionEvent(@Body request: DeletionEventRequest): Response<Map<String, Any>>

    @POST("/api/v1/relationships/action")
    suspend fun updateRelationshipState(@Body request: RelationshipActionRequest): Response<Map<String, Any>>

    @POST("/api/v1/tokens/temporary")
    suspend fun createTemporaryToken(@Body request: TemporaryTokenRequest): Response<TemporaryTokenResponse>

    @POST("/api/v1/push/register")
    suspend fun registerPushToken(@Body request: PushTokenRequest): Response<Map<String, Any>>

    @GET("/api/v1/calls/pending/{deviceId}")
    suspend fun fetchPendingCallSignals(
        @Path("deviceId") deviceId: String,
        @Header("X-Enclave-Timestamp") timestampMs: Long,
        @Header("X-Enclave-Signature") signatureB64: String,
    ): Response<PendingSignalsResponse>

    @GET("/api/v1/rtc/ice-config/{deviceId}")
    suspend fun getIceConfig(@Path("deviceId") deviceId: String): Response<TurnCredentialsResponse>

    @POST("/api/v1/media/upload")
    suspend fun uploadEncryptedMedia(@Body request: UploadMediaRequest): Response<UploadMediaResponse>

    @GET("/api/v1/media/{mediaId}")
    suspend fun downloadEncryptedMedia(
        @Path("mediaId") mediaId: String,
        @Header("X-Enclave-Device-Id") deviceId: String,
        @Header("X-Enclave-Timestamp") timestampMs: Long,
        @Header("X-Enclave-Signature") signatureB64: String,
    ): Response<DownloadMediaResponse>
}

object BackendClientFactory {
    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    fun createOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // Never log HTTP bodies so zero encrypted payloads or metadata leak into system logs
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    fun createApiService(baseUrl: String, okHttpClient: OkHttpClient): EnclaveApiService {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(EnclaveApiService::class.java)
    }
}
