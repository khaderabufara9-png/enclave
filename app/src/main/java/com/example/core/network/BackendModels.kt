package com.example.core.network

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RegisterDeviceRequest(
    val installationId: String,
    val signingPublicKeyB64: String,
    val ecdhPublicKeyB64: String,
    val platform: String = "android",
    val signatureB64: String,
    val timestampMs: Long,
)

@JsonClass(generateAdapter = true)
data class RegisterDeviceResponse(
    val registered: Boolean,
    val serverTimeMs: Long,
    val fcmConfigured: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class PublishInvitationRequest(
    val invitationId: String,
    val code: String,
    val inviterDeviceId: String,
    val inviterSigningPublicKeyB64: String,
    val inviterEcdhPublicKeyB64: String,
    val encryptedProfileCardB64: String = "",
    val offeredDisplayName: String,
    val expiresAtMs: Long,
    val signatureB64: String,
)

@JsonClass(generateAdapter = true)
data class LookupInvitationResponse(
    val invitationId: String,
    val code: String,
    val inviterDeviceId: String,
    val inviterSigningPublicKeyB64: String,
    val inviterEcdhPublicKeyB64: String,
    val offeredDisplayName: String,
    val expiresAtMs: Long,
    val signatureB64: String,
    val status: String,
)

@JsonClass(generateAdapter = true)
data class AcceptInvitationRequest(
    val relationshipId: String,
    val responderDeviceId: String,
    val responderSigningPublicKeyB64: String,
    val responderEcdhPublicKeyB64: String,
    val responderDisplayName: String,
    val signatureB64: String,
)

@JsonClass(generateAdapter = true)
data class RelayMessageRequest(
    val messageId: String,
    val relationshipId: String,
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val ivB64: String,
    val ciphertextB64: String,
    val sequence: Long,
    val ttlSeconds: Long,
    val signatureB64: String,
    val sentAtMs: Long,
)

@JsonClass(generateAdapter = true)
data class RelayMessageResponse(
    val messageId: String,
    val status: String, // DELIVERED_WS, QUEUED_OFFLINE
    val serverTimestampMs: Long,
)

@JsonClass(generateAdapter = true)
data class PendingRelayEnvelope(
    val messageId: String,
    val relationshipId: String,
    val senderDeviceId: String,
    val ivB64: String,
    val ciphertextB64: String,
    val sequence: Long,
    val ttlSeconds: Long,
    val signatureB64: String,
    val sentAtMs: Long,
)

@JsonClass(generateAdapter = true)
data class PendingEnvelopesResponse(
    val envelopes: List<PendingRelayEnvelope>,
)

@JsonClass(generateAdapter = true)
data class AckEnvelopeRequest(
    val messageId: String,
    val recipientDeviceId: String,
    val signatureB64: String,
)

@JsonClass(generateAdapter = true)
data class DeletionEventRequest(
    val eventId: String,
    val relationshipId: String,
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val scope: String,
    val targetMessageId: String? = null,
    val signatureB64: String,
    val createdAtMs: Long,
)

@JsonClass(generateAdapter = true)
data class RelationshipActionRequest(
    val relationshipId: String,
    val actorDeviceId: String,
    val peerDeviceId: String,
    val action: String, // REVOKE, BLOCK, UNBLOCK
    val signatureB64: String,
)

@JsonClass(generateAdapter = true)
data class TemporaryTokenRequest(
    val purpose: String, // CALL_TRANSFER, CLICK_TO_CALL
    val deviceId: String,
    val callId: String? = null,
    val ttlSeconds: Int = 300,
    val signatureB64: String,
)

@JsonClass(generateAdapter = true)
data class TemporaryTokenResponse(
    val token: String,
    val url: String,
    val expiresAtMs: Long,
)

@JsonClass(generateAdapter = true)
data class PushTokenRequest(
    val deviceId: String,
    val fcmToken: String,
    val signatureB64: String,
)

@JsonClass(generateAdapter = true)
data class HealthResponse(
    val status: String,
    val postgres: String,
    val redis: String,
    val stunServer: String,
    val turnServer: String,
    val turnConfigured: Boolean = false,
    val fcmConfigured: Boolean = false,
    val publicBaseUrl: String = "",
    val timestampMs: Long,
)

@JsonClass(generateAdapter = true)
data class PendingSignalsResponse(
    val signals: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TurnCredentialsResponse(
    val urls: List<String>,
    val username: String,
    val credential: String,
    val ttlSeconds: Int,
)

@JsonClass(generateAdapter = true)
data class UploadMediaRequest(
    val mediaId: String,
    val uploaderDeviceId: String,
    val ciphertextB64: String,
    val sizeBytes: Long,
    val timestampMs: Long,
    val signatureB64: String,
)

@JsonClass(generateAdapter = true)
data class UploadMediaResponse(
    val mediaId: String,
    val uploaded: Boolean,
    val expiresAtMs: Long,
)

@JsonClass(generateAdapter = true)
data class DownloadMediaResponse(
    val mediaId: String,
    val ciphertextB64: String,
    val sizeBytes: Long,
    val expiresAtMs: Long,
)

