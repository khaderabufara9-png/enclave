package com.example.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey val spaceId: String,
    val name: String,
    val iconName: String,
    val accentHex: String,
    val isHidden: Boolean = false,
    val secretSaltB64: String = "",
    val secretHashB64: String = "",
    val notificationsEnabled: Boolean = true,
    val defaultDisappearingSeconds: Long = 0L,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val profileId: String,
    val spaceId: String,
    val displayName: String,
    val bio: String = "",
    val avatarLocalPath: String? = null,
    val enclaveCode: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "relationships",
    indices = [
        Index(value = ["spaceId"]),
        Index(value = ["peerDeviceId"])
    ]
)
data class RelationshipEntity(
    @PrimaryKey val relationshipId: String,
    val spaceId: String,
    val peerDeviceId: String,
    val peerSigningPublicKeyB64: String,
    val peerEcdhPublicKeyB64: String,
    val peerDisplayName: String,
    val peerBio: String = "",
    val peerAvatarPath: String? = null,
    // Independent public-facing identity shown ONLY to this contact
    val myDisplayName: String,
    val myBio: String = "",
    val myAvatarPath: String? = null,
    val wrappedRootKeyB64: String,
    val safetyFingerprint: String,
    val sendSequence: Long = 0L,
    val recvSequence: Long = 0L,
    val status: String = "ACTIVE", // PENDING_IN, PENDING_OUT, ACTIVE, BLOCKED, REVOKED
    val allowMessaging: Boolean = true,
    val allowIncomingCalls: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val disappearingTtlSeconds: Long = 0L,
    val lastKeyRotationAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "invitations",
    indices = [Index(value = ["code"], unique = true)]
)
data class InvitationEntity(
    @PrimaryKey val invitationId: String,
    val code: String,
    val spaceId: String,
    val direction: String, // OUTGOING, INCOMING
    val peerDeviceId: String = "",
    val peerDisplayName: String = "",
    val peerSigningPublicKeyB64: String = "",
    val peerEcdhPublicKeyB64: String = "",
    val offeredDisplayName: String,
    val offeredBio: String = "",
    val signatureB64: String,
    val status: String = "PENDING", // PENDING, ACCEPTED, REJECTED, EXPIRED
    val expiresAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["spaceId"]), Index(value = ["relationshipId"], unique = true)]
)
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val relationshipId: String,
    val spaceId: String,
    val lastMessagePreview: String = "",
    val lastMessageAt: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val pendingRemoteClearStatus: String = "NONE", // NONE, PENDING_PEER_ACK, CLEARED_BOTH
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "createdAt"]),
        Index(value = ["expiresAt"])
    ]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val relationshipId: String,
    val spaceId: String,
    val senderDeviceId: String,
    val isOutgoing: Boolean,
    val type: String, // TEXT, IMAGE, VIDEO, AUDIO, VOICE, DOCUMENT, LOCATION, SYSTEM
    val bodyText: String,
    val mediaLocalPath: String? = null,
    val mediaMimeType: String? = null,
    val mediaFileName: String? = null,
    val mediaSizeBytes: Long = 0L,
    val mediaDurationMs: Long = 0L,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val replyToMessageId: String? = null,
    val replyPreviewText: String? = null,
    val reactionsJson: String = "{}",
    val deliveryStatus: String = "SENDING", // SENDING, QUEUED_OFFLINE, SENT, DELIVERED, READ, FAILED
    val remoteDeleteStatus: String = "NONE", // NONE, PENDING_PEER_ACK, DELETED_BOTH
    val isEdited: Boolean = false,
    val editedAt: Long? = null,
    val ttlSeconds: Long = 0L,
    val expiresAt: Long? = null,
    val sequenceNumber: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "pending_outbox")
data class PendingOutboxEntity(
    @PrimaryKey val outboxId: String,
    val kind: String, // MESSAGE, DELETION, RECEIPT, PERSONA_UPDATE
    val relationshipId: String,
    val targetDeviceId: String,
    val encryptedPayloadJson: String,
    val retryCount: Int = 0,
    val nextAttemptAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "deletion_events")
data class DeletionEventEntity(
    @PrimaryKey val eventId: String,
    val relationshipId: String,
    val scope: String, // MESSAGE, CONVERSATION, RELATIONSHIP_RESET
    val targetMessageId: String? = null,
    val status: String = "PENDING_ACK", // PENDING_ACK, ACKNOWLEDGED
    val signatureB64: String,
    val createdAt: Long = System.currentTimeMillis(),
    val acknowledgedAt: Long? = null,
)

@Entity(tableName = "call_sessions")
data class CallSessionEntity(
    @PrimaryKey val callId: String,
    val relationshipId: String?,
    val spaceId: String,
    val peerName: String,
    val callType: String, // AUDIO, VIDEO, CLICK_TO_CALL
    val direction: String, // INCOMING, OUTGOING
    val status: String, // RINGING, ACTIVE, ENDED, MISSED, REJECTED, TRANSFERRED
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val durationSeconds: Long = 0L,
    val qualityLabel: String = "HD",
)

@Entity(tableName = "group_calls")
data class GroupCallEntity(
    @PrimaryKey val groupCallId: String,
    val spaceId: String,
    val title: String,
    val hostDeviceId: String,
    val participantIdsJson: String,
    val isVideo: Boolean = false,
    val status: String = "ACTIVE", // ACTIVE, ENDED
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "security_audit")
data class SecurityAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis(),
)
