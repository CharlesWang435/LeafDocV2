package com.leafdoc.app.data.remote.ai

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.leafdoc.app.data.model.DiseaseInfo
import com.leafdoc.app.data.model.DiseaseSeverity
import com.leafdoc.app.data.model.DiagnosisDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic Claude AI provider implementation.
 * Uses Claude 3.5 Sonnet for detailed, nuanced corn leaf disease diagnosis.
 */
@Singleton
class ClaudeAiProvider @Inject constructor(
    private val gson: Gson,
    private val okHttpClient: OkHttpClient,
    private val apiKey: String
) : AiProvider {

    companion object {
        private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
        private const val CLAUDE_MODEL = "claude-3-5-sonnet-20241022"
        private const val CLAUDE_API_VERSION = "2023-06-01"
        private const val MAX_IMAGE_SIZE_BYTES = 5_000_000 // 5MB limit for Claude
    }

    override suspend fun analyzeLeafImage(
        sessionId: String,
        bitmap: Bitmap,
        promptText: String,
        latitude: Double?,
        longitude: Double?
    ): Result<DiagnosisDisplay> = withContext(Dispatchers.IO) {
        return@withContext try {
            // Convert bitmap to base64
            val base64Image = bitmapToBase64(bitmap)

            // Build Claude API request
            val requestBody = ClaudeRequest(
                model = CLAUDE_MODEL,
                maxTokens = 2048,
                messages = listOf(
                    ClaudeMessage(
                        role = "user",
                        content = listOf(
                            ClaudeContent.ImageContent(
                                type = "image",
                                source = ClaudeImageSource(
                                    type = "base64",
                                    mediaType = "image/jpeg",
                                    data = base64Image
                                )
                            ),
                            ClaudeContent.TextContent(
                                type = "text",
                                text = promptText
                            )
                        )
                    )
                )
            )

            val requestBodyJson = gson.toJson(requestBody)

            val request = Request.Builder()
                .url(CLAUDE_API_URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", CLAUDE_API_VERSION)
                .addHeader("content-type", "application/json")
                .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                    ?: throw Exception("Empty response from Claude")

                if (!response.isSuccessful) {
                    Timber.e("Claude API error: ${response.code} - $responseBody")
                    throw Exception("Claude API error: ${response.code} - ${response.message}")
                }

                Timber.d("Claude response: $responseBody")

                parseClaudeResponse(sessionId, responseBody)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to analyze leaf image with Claude")
            Result.failure(e)
        }
    }

    override fun isConfigured(): Boolean {
        return apiKey.isNotBlank()
    }

    override fun getProviderType(): String = "Claude"

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        var quality = 95
        var compressedBytes: ByteArray

        // Compress until under size limit
        do {
            outputStream.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            compressedBytes = outputStream.toByteArray()
            quality -= 5
        } while (compressedBytes.size > MAX_IMAGE_SIZE_BYTES && quality > 10)

        return Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
    }

    private fun parseClaudeResponse(sessionId: String, responseBody: String): Result<DiagnosisDisplay> {
        return try {
            val claudeResponse = gson.fromJson(responseBody, ClaudeResponse::class.java)

            // Extract text content from response
            val textContent = claudeResponse.content
                .firstOrNull { it is ClaudeResponseContent.TextContent }
                as? ClaudeResponseContent.TextContent
                ?: throw Exception("No text content in Claude response")

            val responseText = textContent.text

            // Extract JSON from response
            val jsonText = extractJsonFromResponse(responseText)

            val diagnosisResult = gson.fromJson(jsonText, ClaudeDiagnosisResult::class.java)

            val diseases = diagnosisResult.diseases?.map { disease ->
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
                isHealthy = diagnosisResult.is_healthy ?: true,
                healthScore = diagnosisResult.health_score ?: 100,
                primaryDiagnosis = diagnosisResult.primary_diagnosis ?: "Healthy",
                confidence = diagnosisResult.confidence ?: 0,
                diseases = diseases.sortedByDescending { it.probability },
                suggestions = diagnosisResult.suggestions ?: listOf("Continue regular monitoring."),
                leafDescription = diagnosisResult.leaf_description,
                analyzedAt = System.currentTimeMillis()
            )

            Result.success(diagnosis)
        } catch (e: JsonSyntaxException) {
            Timber.e(e, "Failed to parse Claude JSON response")
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing Claude response")
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

// Claude API request models
private data class ClaudeRequest(
    val model: String,
    @SerializedName("max_tokens")
    val maxTokens: Int,
    val messages: List<ClaudeMessage>
)

private data class ClaudeMessage(
    val role: String,
    val content: List<ClaudeContent>
)

private sealed class ClaudeContent {
    data class TextContent(
        val type: String,
        val text: String
    ) : ClaudeContent()

    data class ImageContent(
        val type: String,
        val source: ClaudeImageSource
    ) : ClaudeContent()
}

private data class ClaudeImageSource(
    val type: String,
    @SerializedName("media_type")
    val mediaType: String,
    val data: String
)

// Claude API response models
private data class ClaudeResponse(
    val content: List<ClaudeResponseContent>,
    val model: String,
    val role: String
)

private sealed class ClaudeResponseContent {
    data class TextContent(
        val type: String,
        val text: String
    ) : ClaudeResponseContent()
}

// Diagnosis result parsing models
private data class ClaudeDiagnosisResult(
    val is_healthy: Boolean?,
    val health_score: Int?,
    val primary_diagnosis: String?,
    val confidence: Int?,
    val leaf_description: String?,
    val diseases: List<ClaudeDisease>?,
    val suggestions: List<String>?
)

private data class ClaudeDisease(
    val name: String?,
    val common_name: String?,
    val probability: Int?,
    val severity: String?,
    val description: String?,
    val treatments: List<String>?
)
