package com.leafdoc.app.di

import android.content.Context
import androidx.room.Room
import com.leafdoc.app.data.local.LeafDocDatabase
import com.leafdoc.app.data.local.LeafSegmentDao
import com.leafdoc.app.data.local.LeafSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LeafDocDatabase {
        return Room.databaseBuilder(
            context,
            LeafDocDatabase::class.java,
            LeafDocDatabase.DATABASE_NAME
        )
            .addMigrations(*LeafDocDatabase.ALL_MIGRATIONS)
            // In development, you can use fallbackToDestructiveMigration()
            // Remove this in production when you have proper migrations
            // .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideLeafSessionDao(database: LeafDocDatabase): LeafSessionDao {
        return database.leafSessionDao()
    }

    @Provides
    @Singleton
    fun provideLeafSegmentDao(database: LeafDocDatabase): LeafSegmentDao {
        return database.leafSegmentDao()
    }
}
