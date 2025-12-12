package com.leafdoc.app.di

import com.google.gson.Gson
import com.leafdoc.app.BuildConfig
import com.leafdoc.app.data.remote.ai.AiProviderFactory
import com.leafdoc.app.data.remote.ai.ChatGptAiProvider
import com.leafdoc.app.data.remote.ai.ClaudeAiProvider
import com.leafdoc.app.data.remote.ai.GeminiAiProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Hilt module for providing AI-related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideGeminiAiProvider(gson: Gson): GeminiAiProvider {
        return GeminiAiProvider(
            gson = gson,
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    @Provides
    @Singleton
    fun provideClaudeAiProvider(
        gson: Gson,
        okHttpClient: OkHttpClient
    ): ClaudeAiProvider {
        return ClaudeAiProvider(
            gson = gson,
            okHttpClient = okHttpClient,
            apiKey = BuildConfig.CLAUDE_API_KEY
        )
    }

    @Provides
    @Singleton
    fun provideChatGptAiProvider(
        gson: Gson,
        okHttpClient: OkHttpClient
    ): ChatGptAiProvider {
        return ChatGptAiProvider(
            gson = gson,
            okHttpClient = okHttpClient,
            apiKey = BuildConfig.CHATGPT_API_KEY
        )
    }

    @Provides
    @Singleton
    fun provideAiProviderFactory(
        geminiProvider: GeminiAiProvider,
        claudeProvider: ClaudeAiProvider,
        chatGptProvider: ChatGptAiProvider
    ): AiProviderFactory {
        return AiProviderFactory(
            geminiProvider = geminiProvider,
            claudeProvider = claudeProvider,
            chatGptProvider = chatGptProvider
        )
    }
}
