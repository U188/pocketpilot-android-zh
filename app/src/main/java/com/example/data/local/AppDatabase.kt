package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ServerProfileEntity::class,
        KnownHostEntity::class,
        TerminalSnippetEntity::class,
        UsageMessageEntity::class,
        ModelPricingEntity::class,
        ManualBalanceEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun terminalSnippetDao(): TerminalSnippetDao
    abstract fun usageDao(): UsageDao
    abstract fun modelPricingDao(): ModelPricingDao
    abstract fun manualBalanceDao(): ManualBalanceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `terminal_snippets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `command` TEXT NOT NULL,
                        `autoExecute` INTEGER NOT NULL DEFAULT 1,
                        `createdAtMillis` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_messages` (
                        `serverProfileId` INTEGER NOT NULL,
                        `workspacePath` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `timestampMillis` INTEGER NOT NULL,
                        `inputTokens` INTEGER NOT NULL,
                        `outputTokens` INTEGER NOT NULL,
                        `reasoningTokens` INTEGER NOT NULL,
                        `cacheReadTokens` INTEGER NOT NULL,
                        `cacheWriteTokens` INTEGER NOT NULL,
                        `serverCostUsd` REAL NOT NULL,
                        PRIMARY KEY(`serverProfileId`, `sessionId`, `messageId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_usage_messages_serverProfileId_dayKey` ON `usage_messages` (`serverProfileId`, `dayKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_usage_messages_serverProfileId_workspacePath` ON `usage_messages` (`serverProfileId`, `workspacePath`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `model_pricing` (
                        `providerId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `multiplier` REAL NOT NULL,
                        `cacheMultiplier` REAL NOT NULL,
                        `inputPricePer1M` REAL,
                        `outputPricePer1M` REAL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`providerId`, `modelId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `manual_balance` (
                        `scopeKey` TEXT NOT NULL,
                        `initialBalanceUnits` REAL NOT NULL,
                        `enteredAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`scopeKey`)
                    )
                    """.trimIndent(),
                )
            }
        }

        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "opencode_local.db",
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
}
