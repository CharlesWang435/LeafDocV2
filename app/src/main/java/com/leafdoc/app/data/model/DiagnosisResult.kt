package com.leafdoc.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Response from the cloud AI diagnosis service.
 */
data class DiagnosisResponse(
    @SerializedName("is_healthy")
    val isHealthy: Boolean,
    @SerializedName("health_probability")
    val healthProbability: Float,
    val diseases: List<DiseaseDetection>,
    @SerializedName("analysis_timestamp")
    val analysisTimestamp: Long,
    val suggestions: List<String>
)

data class DiseaseDetection(
    val name: String,
    @SerializedName("common_name")
    val commonName: String?,
    val probability: Float,
    val description: String?,
    val treatment: List<String>?,
    @SerializedName("affected_area_percentage")
    val affectedAreaPercentage: Float?
)

/**
 * Local representation of a diagnosis for display.
 */
data class DiagnosisDisplay(
    val sessionId: String,
    val isHealthy: Boolean,
    val healthScore: Int,  // 0-100
    val primaryDiagnosis: String?,
    val confidence: Int,  // 0-100
    val diseases: List<DiseaseInfo>,
    val suggestions: List<String>,
    val analyzedAt: Long
)

data class DiseaseInfo(
    val name: String,
    val commonName: String?,
    val probability: Int,  // 0-100
    val description: String?,
    val treatments: List<String>,
    val severity: DiseaseSeverity
)

enum class DiseaseSeverity {
    LOW,
    MODERATE,
    HIGH,
    SEVERE
}
