package com.leafdoc.app.data.remote.ai

import com.leafdoc.app.data.model.AiProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating AI provider instances based on user selection.
 */
@Singleton
class AiProviderFactory @Inject constructor(
    private val geminiProvider: GeminiAiProvider,
    private val claudeProvider: ClaudeAiProvider,
    private val chatGptProvider: ChatGptAiProvider
) {
    /**
     * Gets the AI provider for the specified type.
     * Returns null if the provider is not configured (missing API key).
     */
    fun getProvider(providerType: AiProviderType): AiProvider? {
        val provider = when (providerType) {
            AiProviderType.GEMINI -> geminiProvider
            AiProviderType.CLAUDE -> claudeProvider
            AiProviderType.CHATGPT -> chatGptProvider
        }

        return if (provider.isConfigured()) provider else null
    }

    /**
     * Gets all configured providers (those with valid API keys).
     */
    fun getConfiguredProviders(): List<Pair<AiProviderType, AiProvider>> {
        return listOf(
            AiProviderType.GEMINI to geminiProvider,
            AiProviderType.CLAUDE to claudeProvider,
            AiProviderType.CHATGPT to chatGptProvider
        ).filter { (_, provider) -> provider.isConfigured() }
    }

    /**
     * Checks if a specific provider is configured.
     */
    fun isProviderConfigured(providerType: AiProviderType): Boolean {
        return getProvider(providerType) != null
    }
}
