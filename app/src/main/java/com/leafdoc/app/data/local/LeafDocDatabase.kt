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
    version = 1,
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
         *
         * Example migration from version 1 to 2:
         * val MIGRATION_1_2 = object : Migration(1, 2) {
         *     override fun migrate(database: SupportSQLiteDatabase) {
         *         database.execSQL("ALTER TABLE leaf_sessions ADD COLUMN notes TEXT")
         *     }
         * }
         */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            // Add migrations here as needed, e.g.:
            // MIGRATION_1_2,
            // MIGRATION_2_3,
        )
    }
}
