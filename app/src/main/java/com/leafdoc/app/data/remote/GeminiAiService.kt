package com.leafdoc.app.data.remote

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.content
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.leafdoc.app.BuildConfig
import com.leafdoc.app.data.model.DiseaseInfo
import com.leafdoc.app.data.model.DiseaseSeverity
import com.leafdoc.app.data.model.DiagnosisDisplay
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiAiService @Inject constructor(
    private val gson: Gson
) {
    private val generativeModel: GenerativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY,
            generationConfig = GenerationConfig.Builder().apply {
                temperature = 0.2f
                topK = 40
                topP = 0.95f
            }.build()
        )
    }

    suspend fun analyzeLeafImage(
        sessionId: String,
        bitmap: Bitmap,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<DiagnosisDisplay> {
        return try {
            val prompt = buildAnalysisPrompt(latitude, longitude)

            val content = content {
                image(bitmap)
                text(prompt)
            }

            val response = generativeModel.generateContent(content)
            val responseText = response.text ?: throw Exception("Empty response from Gemini")

            Timber.d("Gemini response: $responseText")

            parseGeminiResponse(sessionId, responseText)
        } catch (e: Exception) {
            Timber.e(e, "Failed to analyze leaf image with Gemini")
            Result.failure(e)
        }
    }

    suspend fun analyzeLeafImageFromFile(
        sessionId: String,
        imageFile: File,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<DiagnosisDisplay> {
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: throw Exception("Failed to decode image file")

            val result = analyzeLeafImage(sessionId, bitmap, latitude, longitude)
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Timber.e(e, "Failed to analyze leaf image from file")
            Result.failure(e)
        }
    }

    private fun buildAnalysisPrompt(latitude: Double?, longitude: Double?): String {
        val locationContext = if (latitude != null && longitude != null) {
            "\nLocation: Latitude $latitude, Longitude $longitude"
        } else ""

        return """
You are an expert agricultural pathologist specializing in corn (Zea mays) diseases.
Analyze the provided leaf image for disease symptoms and provide a detailed assessment.

IMAGE CONTEXT:
- Imaging method: Transmittance imaging (backlit leaf, midrib visible)
- Crop: Corn/Maize (Zea mays)$locationContext

ANALYSIS REQUIREMENTS:
1. Provide a detailed visual description of the corn leaf
2. Assess overall leaf health (healthy vs diseased)
3. Identify specific diseases if present
4. Estimate disease severity
5. Provide treatment recommendations

IMPORTANT NOTES:
- The health_score is an ESTIMATED indicator only, not a definitive diagnosis
- This analysis should be used as a preliminary assessment to guide further investigation
- Always recommend consulting with local agricultural experts for confirmation

CORN DISEASE DATABASE (Focus on these common diseases):
- Northern Corn Leaf Blight (Exserohilum turcicum) - Long, elliptical gray-green lesions
- Gray Leaf Spot (Cercospora zeae-maydis) - Rectangular gray/tan lesions
- Southern Corn Leaf Blight (Cochliobolus heterostrophus) - Small tan lesions with dark borders
- Common Rust (Puccinia sorghi) - Orange-brown pustules on both leaf surfaces
- Anthracnose (Colletotrichum graminicola) - Irregular necrotic lesions
- Goss's Wilt (Clavibacter michiganensis) - Water-soaked lesions with freckles
- Eyespot (Aureobasidium zeae) - Small circular lesions with tan centers
- Holcus Spot (Pseudomonas syringae) - Small round tan spots
- Tar Spot (Phyllachora maydis) - Raised black spots
- Diplodia Leaf Streak - Long tan streaks
- Physoderma Brown Spot - Yellow-brown spots in bands

VISUAL INDICATORS IN TRANSMITTANCE IMAGING:
- Healthy: Uniform green translucence, clear midrib, no lesions
- Diseased: Dark spots/lesions, necrotic areas, discoloration, irregular patterns

OUTPUT FORMAT (Respond with ONLY valid JSON, no other text):
{
  "is_healthy": true/false,
  "health_score": 0-100,
  "primary_diagnosis": "disease name or Healthy",
  "confidence": 0-100,
  "leaf_description": "Detailed description of the corn leaf including: overall appearance, color and coloration patterns, leaf size/shape observations, midrib condition, surface texture, any visible lesions or spots (location, size, shape, color), areas of discoloration or necrosis, and general tissue health. Be thorough and descriptive.",
  "diseases": [
    {
      "name": "scientific name",
      "common_name": "common name",
      "probability": 0-100,
      "severity": "LOW" or "MODERATE" or "HIGH" or "SEVERE",
      "description": "brief description of observed symptoms matching this disease",
      "treatments": ["treatment1", "treatment2"]
    }
  ],
  "suggestions": ["actionable recommendation 1", "recommendation 2"]
}

DESCRIPTION GUIDELINES for leaf_description:
- Start with overall leaf condition (healthy appearance, signs of stress, etc.)
- Describe the coloration: base color, any yellowing, browning, or unusual hues
- Note the midrib: visibility, color, any abnormalities
- Detail any lesions: quantity, distribution pattern, size range, shape, color, borders
- Mention tissue health: turgidity, wilting, necrotic areas
- Include observations about leaf edges and tip condition
- Note any patterns (random vs systematic distribution of symptoms)

Be precise and conservative. If uncertain, provide multiple disease candidates with probabilities.
If the image is not a corn leaf or is unclear, set is_healthy to true and confidence to 0 with appropriate suggestions.
Note: The health_score is an estimate based on visual analysis and should not be treated as a definitive measurement.
""".trimIndent()
    }

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
            Timber.e(e, "Failed to parse Gemini JSON response: $responseText")
            // Return a fallback diagnosis if parsing fails
            Result.success(createFallbackDiagnosis(sessionId, responseText))
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

    private fun createFallbackDiagnosis(sessionId: String, responseText: String): DiagnosisDisplay {
        // Create a basic diagnosis from unparseable response
        val lowerResponse = responseText.lowercase()
        val isHealthy = !lowerResponse.contains("disease") &&
                       !lowerResponse.contains("infected") &&
                       !lowerResponse.contains("blight") &&
                       !lowerResponse.contains("rust")

        return DiagnosisDisplay(
            sessionId = sessionId,
            isHealthy = isHealthy,
            healthScore = if (isHealthy) 80 else 50,
            primaryDiagnosis = if (isHealthy) "Healthy" else "Possible Disease Detected",
            confidence = 30,
            diseases = emptyList(),
            suggestions = listOf(
                "Analysis completed with limited confidence.",
                "Consider retaking the image with better lighting.",
                "Consult a local agricultural expert for confirmation."
            ),
            leafDescription = "Unable to generate detailed description. The image may be unclear or the analysis encountered an issue.",
            analyzedAt = System.currentTimeMillis()
        )
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
