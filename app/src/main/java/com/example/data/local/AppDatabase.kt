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
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun terminalSnippetDao(): TerminalSnippetDao

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

        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "opencode_local.db",
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
