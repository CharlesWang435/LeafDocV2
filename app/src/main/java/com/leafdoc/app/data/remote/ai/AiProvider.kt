package com.leafdoc.app.data.remote.ai

import android.graphics.Bitmap
import com.leafdoc.app.data.model.DiagnosisDisplay

/**
 * Common interface for all AI diagnosis providers.
 * Implementations handle provider-specific API calls and response parsing.
 */
interface AiProvider {
    /**
     * Analyzes a corn leaf image for disease diagnosis.
     *
     * @param sessionId Unique identifier for this analysis session
     * @param bitmap The leaf image to analyze
     * @param promptText The complete prompt text (built from template + context)
     * @param latitude Optional GPS latitude for location-based disease context
     * @param longitude Optional GPS longitude for location-based disease context
     * @return Result containing DiagnosisDisplay on success or exception on failure
     */
    suspend fun analyzeLeafImage(
        sessionId: String,
        bitmap: Bitmap,
        promptText: String,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<DiagnosisDisplay>

    /**
     * Returns true if the provider is configured with a valid API key.
     */
    fun isConfigured(): Boolean

    /**
     * Returns the provider type.
     */
    fun getProviderType(): String
}
