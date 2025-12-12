package com.leafdoc.app.data.model

/**
 * Supported AI providers for corn leaf diagnosis.
 */
enum class AiProviderType {
    GEMINI,    // Google Gemini 2.5 Flash
    CLAUDE,    // Anthropic Claude 3.5 Sonnet
    CHATGPT;   // OpenAI GPT-4o

    val displayName: String
        get() = when (this) {
            GEMINI -> "Google Gemini"
            CLAUDE -> "Anthropic Claude"
            CHATGPT -> "OpenAI ChatGPT"
        }

    val description: String
        get() = when (this) {
            GEMINI -> "Fast, accurate analysis with Google's Gemini 2.5 Flash model"
            CLAUDE -> "Detailed, nuanced diagnosis with Anthropic's Claude 3.5 Sonnet"
            CHATGPT -> "Comprehensive analysis with OpenAI's GPT-4o model"
        }

    /**
     * Cost estimation per analysis (in USD cents)
     */
    val estimatedCostPerAnalysis: Int
        get() = when (this) {
            GEMINI -> 2   // ~$0.02 per analysis
            CLAUDE -> 5   // ~$0.05 per analysis
            CHATGPT -> 4  // ~$0.04 per analysis
        }
}
