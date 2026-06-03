package com.leafdoc.app.data.remote.ai

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.content
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.leafdoc.app.data.model.DiseaseInfo
import com.leafdoc.app.data.model.DiseaseSeverity
import com.leafdoc.app.data.model.DiagnosisDisplay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Gemini AI provider implementation.
 * Uses Gemini 2.5 Flash for fast, accurate corn leaf disease diagnosis.
 */
@Singleton
class GeminiAiProvider @Inject constructor(
    private val gson: Gson,
    private val apiKey: String
) : AiProvider {

    private fun buildModel(temperature: Float, maxTokens: Int): GenerativeModel {
        if (!isConfigured()) {
            throw IllegalStateException("Gemini API key not configured")
        }
        return GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = apiKey,
            generationConfig = GenerationConfig.Builder().apply {
                this.temperature = temperature
                topK = 40
                topP = 0.95f
                maxOutputTokens = maxTokens
            }.build()
        )
    }

    override suspend fun analyzeLeafImage(
        sessionId: String,
        bitmap: Bitmap,
        promptText: String,
        latitude: Double?,
        longitude: Double?,
        temperature: Float,
        maxTokens: Int
    ): Result<DiagnosisDisplay> {
        return try {
            val content = content {
                image(bitmap)
                text(promptText)
            }

            val response = buildModel(temperature, maxTokens).generateContent(content)
            val responseText = response.text ?: throw Exception("Empty response from Gemini")

            Timber.d("Gemini response received (%d chars)", responseText.length)

            parseGeminiResponse(sessionId, responseText)
        } catch (e: Exception) {
            Timber.e(e, "Failed to analyze leaf image with Gemini")
            Result.failure(e)
        }
    }

    override fun isConfigured(): Boolean {
        return apiKey.isNotBlank()
    }

    override fun getProviderType(): String = "Gemini"

    private fun parseGeminiResponse(sessionId: String, responseText: String): Result<DiagnosisDisplay> {
        return try {
            // Extract JSON from response (Gemini might include markdown code blocks)
            val jsonText = extractJsonFromResponse(responseText)

            val geminiResult = gson.fromJson(jsonText, GeminiDiagnosisResult::class.java)

            val diseases = geminiResult.diseases?.map { disease ->
                DiseaseInfo(
                    name = disease.name ?: "Unknown",
                    commonName = disease.common_name,
                    probability = disease.probability ?: 0,
                    description = disease.description,
                    treatments = disease.treatments ?: emptyList(),
                    severity = when (disease.severity?.uppercase()) {
                        "SEVERE" -> DiseaseSeverity.SEVERE
                        "HIGH" -> DiseaseSeverity.HIGH
                        "MODERATE" -> DiseaseSeverity.MODERATE
                        else -> DiseaseSeverity.LOW
                    }
                )
            } ?: emptyList()

            val diagnosis = DiagnosisDisplay(
                sessionId = sessionId,
                isHealthy = geminiResult.is_healthy ?: true,
                healthScore = geminiResult.health_score ?: 100,
                primaryDiagnosis = geminiResult.primary_diagnosis ?: "Healthy",
                confidence = geminiResult.confidence ?: 0,
                diseases = diseases.sortedByDescending { it.probability },
                suggestions = geminiResult.suggestions ?: listOf("Continue regular monitoring."),
                leafDescription = geminiResult.leaf_description,
                analyzedAt = System.currentTimeMillis()
            )

            Result.success(diagnosis)
        } catch (e: JsonSyntaxException) {
            // Don't fabricate a verdict from a keyword scan — a malformed response is a
            // failure, not a diagnosis. Surface it so the UI can offer a retry.
            Timber.e(e, "Failed to parse Gemini JSON response (%d chars)", responseText.length)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing Gemini response")
            Result.failure(e)
        }
    }

    private fun extractJsonFromResponse(responseText: String): String {
        // Try to extract JSON from markdown code blocks if present
        val jsonBlockPattern = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = jsonBlockPattern.find(responseText)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // Try to find JSON object directly
        val jsonObjectPattern = Regex("\\{[\\s\\S]*}")
        val objectMatch = jsonObjectPattern.find(responseText)
        if (objectMatch != null) {
            return objectMatch.value
        }

        return responseText.trim()
    }

}

// Internal data classes for Gemini JSON parsing
private data class GeminiDiagnosisResult(
    val is_healthy: Boolean?,
    val health_score: Int?,
    val primary_diagnosis: String?,
    val confidence: Int?,
    val leaf_description: String?,
    val diseases: List<GeminiDisease>?,
    val suggestions: List<String>?
)

private data class GeminiDisease(
    val name: String?,
    val common_name: String?,
    val probability: Int?,
    val severity: String?,
    val description: String?,
    val treatments: List<String>?
)
