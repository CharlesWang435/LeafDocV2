package com.leafdoc.app.data.model

/**
 * User-facing information about an analysis prompt template.
 * The actual prompt text is internal and optimized for best diagnosis results.
 */
data class PromptTemplateInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val estimatedDuration: PromptDuration,
    val detailLevel: DetailLevel,
    val icon: String  // Material icon name
)

enum class PromptDuration {
    QUICK,      // 5-10 seconds
    STANDARD,   // 10-20 seconds
    DETAILED;   // 20-30 seconds

    val displayText: String
        get() = when (this) {
            QUICK -> "5-10 sec"
            STANDARD -> "10-20 sec"
            DETAILED -> "20-30 sec"
        }
}

enum class DetailLevel {
    BASIC,
    MODERATE,
    COMPREHENSIVE;

    val displayText: String
        get() = when (this) {
            BASIC -> "Basic"
            MODERATE -> "Moderate"
            COMPREHENSIVE -> "Comprehensive"
        }
}
