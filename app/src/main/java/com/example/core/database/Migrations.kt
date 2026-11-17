package com.example.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object EnclaveMigrations {
    /**
     * Migration 1 -> 2: Adds key rotation timestamp to relationships and creates composite outbox index.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE relationships ADD COLUMN lastKeyRotationAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_outbox_nextAttemptAt ON pending_outbox(nextAttemptAt)")
        }
    }
}
