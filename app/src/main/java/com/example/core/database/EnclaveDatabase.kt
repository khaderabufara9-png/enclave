package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SpaceEntity::class,
        ProfileEntity::class,
        RelationshipEntity::class,
        InvitationEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        PendingOutboxEntity::class,
        DeletionEventEntity::class,
        CallSessionEntity::class,
        GroupCallEntity::class,
        SecurityAuditEntity::class,
    ],
    version = 2,
    exportSchema = false
)
abstract class EnclaveDatabase : RoomDatabase() {
    abstract fun spaceDao(): SpaceDao
    abstract fun profileDao(): ProfileDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun invitationDao(): InvitationDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun deletionEventDao(): DeletionEventDao
    abstract fun callDao(): CallDao
    abstract fun securityAuditDao(): SecurityAuditDao

    companion object {
        @Volatile
        private var INSTANCE: EnclaveDatabase? = null

        fun getInstance(context: Context): EnclaveDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EnclaveDatabase::class.java,
                    "enclave_vault.db"
                )
                    .addMigrations(EnclaveMigrations.MIGRATION_1_2)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
