package com.leafdoc.app.di

import android.content.Context
import com.google.gson.Gson
import com.leafdoc.app.data.local.LeafSegmentDao
import com.leafdoc.app.data.local.LeafSessionDao
import com.leafdoc.app.data.preferences.UserPreferencesManager
import com.leafdoc.app.data.remote.GeminiAiService
import com.leafdoc.app.data.repository.DiagnosisRepository
import com.leafdoc.app.data.repository.LeafSessionRepository
import com.leafdoc.app.data.repository.ImageRepository
import com.leafdoc.app.util.LocationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideUserPreferencesManager(@ApplicationContext context: Context): UserPreferencesManager {
        return UserPreferencesManager(context)
    }

    @Provides
    @Singleton
    fun provideLeafSessionRepository(
        sessionDao: LeafSessionDao,
        segmentDao: LeafSegmentDao
    ): LeafSessionRepository {
        return LeafSessionRepository(sessionDao, segmentDao)
    }

    @Provides
    @Singleton
    fun provideImageRepository(@ApplicationContext context: Context): ImageRepository {
        return ImageRepository(context)
    }

    @Provides
    @Singleton
    fun provideDiagnosisRepository(
        aiProviderFactory: com.leafdoc.app.data.remote.ai.AiProviderFactory,
        preferencesManager: UserPreferencesManager,
        sessionDao: LeafSessionDao,
        gson: Gson
    ): DiagnosisRepository {
        return DiagnosisRepository(aiProviderFactory, preferencesManager, sessionDao, gson)
    }

    @Provides
    @Singleton
    fun provideLocationManager(@ApplicationContext context: Context): LocationManager {
        return LocationManager(context)
    }
}
