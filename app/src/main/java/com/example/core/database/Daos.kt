package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SpaceDao {
    @Query("SELECT * FROM spaces WHERE isHidden = 0 ORDER BY isDefault DESC, createdAt ASC")
    fun observeVisibleSpaces(): Flow<List<SpaceEntity>>

    @Query("SELECT * FROM spaces")
    suspend fun getAllSpaces(): List<SpaceEntity>

    @Query("SELECT * FROM spaces WHERE spaceId = :spaceId")
    fun observeSpace(spaceId: String): Flow<SpaceEntity?>

    @Query("SELECT * FROM spaces WHERE spaceId = :spaceId")
    suspend fun getSpace(spaceId: String): SpaceEntity?

    @Query("SELECT * FROM spaces WHERE isHidden = 1")
    suspend fun getHiddenSpaces(): List<SpaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSpace(space: SpaceEntity)

    @Query("DELETE FROM spaces WHERE spaceId = :spaceId")
    suspend fun deleteSpace(spaceId: String)

    @Query("DELETE FROM spaces WHERE isHidden = 1")
    suspend fun deleteHiddenSpaces()
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE spaceId = :spaceId LIMIT 1")
    fun observeProfileForSpace(spaceId: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE spaceId = :spaceId LIMIT 1")
    suspend fun getProfileForSpace(spaceId: String): ProfileEntity?

    @Query("SELECT * FROM profiles")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity)
}

@Dao
interface RelationshipDao {
    @Query("SELECT * FROM relationships WHERE spaceId = :spaceId AND status != 'REVOKED' ORDER BY peerDisplayName ASC")
    fun observeRelationshipsInSpace(spaceId: String): Flow<List<RelationshipEntity>>

    @Query("SELECT * FROM relationships WHERE relationshipId = :relationshipId")
    fun observeRelationship(relationshipId: String): Flow<RelationshipEntity?>

    @Query("SELECT * FROM relationships WHERE relationshipId = :relationshipId")
    suspend fun getRelationship(relationshipId: String): RelationshipEntity?

    @Query("SELECT * FROM relationships WHERE peerDeviceId = :peerDeviceId AND spaceId = :spaceId LIMIT 1")
    suspend fun findByPeerDevice(peerDeviceId: String, spaceId: String): RelationshipEntity?

    @Query("SELECT * FROM relationships")
    suspend fun getAllRelationships(): List<RelationshipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelationship(relationship: RelationshipEntity)

    @Update
    suspend fun updateRelationship(relationship: RelationshipEntity)

    @Query("DELETE FROM relationships WHERE relationshipId = :relationshipId")
    suspend fun deleteRelationship(relationshipId: String)
}

@Dao
interface InvitationDao {
    @Query("SELECT * FROM invitations WHERE spaceId = :spaceId ORDER BY createdAt DESC")
    fun observeInvitationsForSpace(spaceId: String): Flow<List<InvitationEntity>>

    @Query("SELECT * FROM invitations WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): InvitationEntity?

    @Query("SELECT * FROM invitations WHERE invitationId = :id")
    suspend fun getById(id: String): InvitationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvitation(invitation: InvitationEntity)

    @Query("UPDATE invitations SET status = :status WHERE invitationId = :id")
    suspend fun updateStatus(id: String, status: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE spaceId = :spaceId ORDER BY isPinned DESC, lastMessageAt DESC")
    fun observeConversationsInSpace(spaceId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId")
    fun observeConversation(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE relationshipId = :relationshipId LIMIT 1")
    suspend fun getByRelationship(relationshipId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :conversationId")
    suspend fun clearUnread(conversationId: String)

    @Query("DELETE FROM conversations WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND bodyText LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun searchMessages(conversationId: String, query: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE messageId = :messageId")
    suspend fun updateDeliveryStatus(messageId: String, status: String)

    @Query("UPDATE messages SET remoteDeleteStatus = :status WHERE messageId = :messageId")
    suspend fun updateRemoteDeleteStatus(messageId: String, status: String)

    @Query("UPDATE messages SET bodyText = :newBody, isEdited = 1, editedAt = :editedAt WHERE messageId = :messageId")
    suspend fun editMessage(messageId: String, newBody: String, editedAt: Long)

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE messageId = :messageId")
    suspend fun updateReactions(messageId: String, reactionsJson: String)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :nowMs")
    suspend fun purgeExpiredMessages(nowMs: Long): Int

    @Query("SELECT * FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :nowMs")
    suspend fun getExpiredMessages(nowMs: Long): List<MessageEntity>
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM pending_outbox ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<PendingOutboxEntity>

    @Query("SELECT COUNT(*) FROM pending_outbox")
    fun observePendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: PendingOutboxEntity)

    @Query("DELETE FROM pending_outbox WHERE outboxId = :outboxId")
    suspend fun remove(outboxId: String)

    @Query("UPDATE pending_outbox SET retryCount = retryCount + 1, nextAttemptAt = :nextAt WHERE outboxId = :outboxId")
    suspend fun markRetry(outboxId: String, nextAt: Long)
}

@Dao
interface DeletionEventDao {
    @Query("SELECT * FROM deletion_events WHERE relationshipId = :relationshipId ORDER BY createdAt DESC")
    fun observeForRelationship(relationshipId: String): Flow<List<DeletionEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: DeletionEventEntity)

    @Query("UPDATE deletion_events SET status = 'ACKNOWLEDGED', acknowledgedAt = :ackAt WHERE eventId = :eventId")
    suspend fun markAcknowledged(eventId: String, ackAt: Long)
}

@Dao
interface CallDao {
    @Query("SELECT * FROM call_sessions WHERE spaceId = :spaceId ORDER BY startedAt DESC")
    fun observeCallsInSpace(spaceId: String): Flow<List<CallSessionEntity>>

    @Query("SELECT * FROM call_sessions WHERE callId = :callId")
    suspend fun getCall(callId: String): CallSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCall(call: CallSessionEntity)

    @Query("SELECT * FROM group_calls WHERE spaceId = :spaceId ORDER BY createdAt DESC")
    fun observeGroupCalls(spaceId: String): Flow<List<GroupCallEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupCall(groupCall: GroupCallEntity)
}

@Dao
interface SecurityAuditDao {
    @Query("SELECT * FROM security_audit ORDER BY timestamp DESC LIMIT 100")
    fun observeRecentEvents(): Flow<List<SecurityAuditEntity>>

    @Insert
    suspend fun logEvent(event: SecurityAuditEntity)
}
