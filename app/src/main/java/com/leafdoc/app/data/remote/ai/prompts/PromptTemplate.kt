package com.leafdoc.app.data.remote.ai.prompts

import com.leafdoc.app.data.model.DetailLevel
import com.leafdoc.app.data.model.PromptDuration
import com.leafdoc.app.data.model.PromptTemplateInfo

/**
 * Internal representation of a prompt template with the full prompt text.
 * These are developer-defined and optimized for diagnosis accuracy.
 */
data class PromptTemplate(
    val id: String,
    val info: PromptTemplateInfo,
    val systemPrompt: String,
    val userPromptTemplate: String,
    val outputFormatInstructions: String,
    val temperature: Float = 0.2f,
    val maxTokens: Int = 2048
) {
    companion object {
        const val LOCATION_PLACEHOLDER = "{{LOCATION_CONTEXT}}"
        const val IMAGING_METHOD_PLACEHOLDER = "{{IMAGING_METHOD}}"
    }

    /**
     * Builds the complete prompt with contextual information.
     */
    fun buildPrompt(
        latitude: Double? = null,
        longitude: Double? = null,
        imagingMethod: String = "Transmittance imaging (backlit leaf, midrib visible)"
    ): String {
        val locationContext = if (latitude != null && longitude != null) {
            "\nLocation: Latitude $latitude, Longitude $longitude"
        } else {
            ""
        }

        val userPrompt = userPromptTemplate
            .replace(LOCATION_PLACEHOLDER, locationContext)
            .replace(IMAGING_METHOD_PLACEHOLDER, imagingMethod)

        return buildString {
            append(systemPrompt)
            append("\n\n")
            append(userPrompt)
            append("\n\n")
            append(outputFormatInstructions)
        }.trim()
    }
}

/**
 * Factory for creating prompt template info (UI-facing data).
 */
object PromptTemplateInfoFactory {
    fun createQuickCheckInfo() = PromptTemplateInfo(
        id = "quick_check",
        displayName = "Quick Health Check",
        description = "Fast assessment of overall leaf health and visible symptoms",
        estimatedDuration = PromptDuration.QUICK,
        detailLevel = DetailLevel.BASIC,
        icon = "speed"  // Material Icons: speed
    )

    fun createStandardAnalysisInfo() = PromptTemplateInfo(
        id = "standard_analysis",
        displayName = "Standard Disease Analysis",
        description = "Balanced analysis identifying diseases with treatment recommendations",
        estimatedDuration = PromptDuration.STANDARD,
        detailLevel = DetailLevel.MODERATE,
        icon = "analytics"  // Material Icons: analytics
    )

    fun createDetailedDiagnosisInfo() = PromptTemplateInfo(
        id = "detailed_diagnosis",
        displayName = "Detailed Pathology Report",
        description = "Comprehensive analysis with severity assessment and detailed leaf description",
        estimatedDuration = PromptDuration.DETAILED,
        detailLevel = DetailLevel.COMPREHENSIVE,
        icon = "biotech"  // Material Icons: biotech
    )

    fun createResearchModeInfo() = PromptTemplateInfo(
        id = "research_mode",
        displayName = "Research-Grade Analysis",
        description = "Maximum detail for research purposes with differential diagnosis",
        estimatedDuration = PromptDuration.DETAILED,
        detailLevel = DetailLevel.COMPREHENSIVE,
        icon = "science"  // Material Icons: science
    )

    fun getAllTemplateInfos(): List<PromptTemplateInfo> = listOf(
        createQuickCheckInfo(),
        createStandardAnalysisInfo(),
        createDetailedDiagnosisInfo(),
        createResearchModeInfo()
    )
}
