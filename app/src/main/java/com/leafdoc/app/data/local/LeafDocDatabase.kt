package com.leafdoc.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.leafdoc.app.data.model.LeafSegment
import com.leafdoc.app.data.model.LeafSession

@Database(
    entities = [LeafSession::class, LeafSegment::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class LeafDocDatabase : RoomDatabase() {
    abstract fun leafSessionDao(): LeafSessionDao
    abstract fun leafSegmentDao(): LeafSegmentDao

    companion object {
        const val DATABASE_NAME = "leafdoc_database"

        /**
         * Migration definitions for database schema changes.
         * Add migrations here as the database evolves.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add frameLabel column for individual frame exports
                database.execSQL(
                    "ALTER TABLE leaf_segments ADD COLUMN frameLabel TEXT DEFAULT NULL"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add treatment column for the new metadata field
                database.execSQL(
                    "ALTER TABLE leaf_sessions ADD COLUMN treatment TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3
        )
    }
}
