package com.leafdoc.app.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.leafdoc.app.data.local.LeafSessionDao
import com.leafdoc.app.data.model.*
import com.leafdoc.app.data.preferences.UserPreferencesManager
import com.leafdoc.app.data.remote.ai.AiProviderFactory
import com.leafdoc.app.data.remote.ai.prompts.PromptLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosisRepository @Inject constructor(
    private val aiProviderFactory: AiProviderFactory,
    private val preferencesManager: UserPreferencesManager,
    private val sessionDao: LeafSessionDao,
    private val gson: Gson
) {
    suspend fun analyzeLeaf(
        sessionId: String,
        imagePath: String,
        latitude: Double? = null,
        longitude: Double? = null,
        overrideProvider: AiProviderType? = null,
        overridePromptId: String? = null
    ): Result<DiagnosisDisplay> = withContext(Dispatchers.IO) {
        try {
            // Update status to uploading
            sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.UPLOADING, null, null)

            // Read image file
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, "Image file not found", null)
                return@withContext Result.failure(Exception("Image file not found"))
            }

            // Load and resize bitmap if needed
            val bitmap = loadAndResizeBitmap(imageFile)
            if (bitmap == null) {
                sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, "Failed to load image", null)
                return@withContext Result.failure(Exception("Failed to load image"))
            }

            // Update status to processing
            sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.PROCESSING, null, null)

            // Use override values or get from user preferences
            val selectedProvider = overrideProvider ?: preferencesManager.aiProvider.first()
            val promptTemplateId = overridePromptId ?: preferencesManager.promptTemplateId.first()

            // Get the AI provider
            val aiProvider = aiProviderFactory.getProvider(selectedProvider)
            if (aiProvider == null) {
                val errorMsg = "AI provider ${selectedProvider.displayName} is not configured. Please check API keys in settings."
                sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, errorMsg, null)
                bitmap.recycle()
                return@withContext Result.failure(Exception(errorMsg))
            }

            // Build prompt from template
            val promptTemplate = PromptLibrary.getTemplateById(promptTemplateId)
                ?: PromptLibrary.getStandardAnalysisTemplate()
            val promptText = promptTemplate.buildPrompt(latitude, longitude)

            Timber.d("Using AI provider: ${selectedProvider.displayName}, Template: ${promptTemplate.info.displayName}")

            // Analyze with selected AI provider
            val result = aiProvider.analyzeLeafImage(
                sessionId = sessionId,
                bitmap = bitmap,
                promptText = promptText,
                latitude = latitude,
                longitude = longitude
            )

            bitmap.recycle()

            result.fold(
                onSuccess = { diagnosis ->
                    // Update database with results
                    sessionDao.updateDiagnosis(
                        sessionId = sessionId,
                        status = DiagnosisStatus.COMPLETED,
                        result = serializeDiagnosis(diagnosis),
                        confidence = diagnosis.confidence / 100f
                    )
                    Result.success(diagnosis)
                },
                onFailure = { error ->
                    Timber.e(error, "${selectedProvider.displayName} analysis failed")
                    sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, error.message, null)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Error in analyzeLeaf")
            sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, e.message, null)
            Result.failure(e)
        }
    }

    suspend fun analyzeLeafFromBitmap(
        sessionId: String,
        bitmap: Bitmap,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<DiagnosisDisplay> = withContext(Dispatchers.IO) {
        try {
            sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.UPLOADING, null, null)

            // Resize bitmap if needed
            val resizedBitmap = resizeBitmapIfNeeded(bitmap)

            sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.PROCESSING, null, null)

            // Get user preferences for AI provider and prompt template
            val selectedProvider = preferencesManager.aiProvider.first()
            val promptTemplateId = preferencesManager.promptTemplateId.first()

            // Get the AI provider
            val aiProvider = aiProviderFactory.getProvider(selectedProvider)
            if (aiProvider == null) {
                val errorMsg = "AI provider ${selectedProvider.displayName} is not configured. Please check API keys in settings."
                sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, errorMsg, null)
                if (resizedBitmap != bitmap) {
                    resizedBitmap.recycle()
                }
                return@withContext Result.failure(Exception(errorMsg))
            }

            // Build prompt from template
            val promptTemplate = PromptLibrary.getTemplateById(promptTemplateId)
                ?: PromptLibrary.getStandardAnalysisTemplate()
            val promptText = promptTemplate.buildPrompt(latitude, longitude)

            val result = aiProvider.analyzeLeafImage(
                sessionId = sessionId,
                bitmap = resizedBitmap,
                promptText = promptText,
                latitude = latitude,
                longitude = longitude
            )

            if (resizedBitmap != bitmap) {
                resizedBitmap.recycle()
            }

            result.fold(
                onSuccess = { diagnosis ->
                    sessionDao.updateDiagnosis(
                        sessionId = sessionId,
                        status = DiagnosisStatus.COMPLETED,
                        result = serializeDiagnosis(diagnosis),
                        confidence = diagnosis.confidence / 100f
                    )
                    Result.success(diagnosis)
                },
                onFailure = { error ->
                    Timber.e(error, "${selectedProvider.displayName} analysis failed")
                    sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, error.message, null)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Error in analyzeLeafFromBitmap")
            sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, e.message, null)
            Result.failure(e)
        }
    }

    private fun loadAndResizeBitmap(file: File): Bitmap? {
        return try {
            // First, get dimensions without loading full bitmap
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            // Calculate sample size to stay under max pixels
            val maxPixels = 4_000_000 // 4 megapixels max for Gemini
            val currentPixels = options.outWidth.toLong() * options.outHeight.toLong()

            val loadOptions = BitmapFactory.Options()
            if (currentPixels > maxPixels) {
                val scale = kotlin.math.sqrt(maxPixels.toDouble() / currentPixels.toDouble())
                val targetWidth = (options.outWidth * scale).toInt()
                val targetHeight = (options.outHeight * scale).toInt()
                loadOptions.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
            }

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, loadOptions)

            // Further resize if still too large
            resizeBitmapIfNeeded(bitmap)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load bitmap from file")
            null
        }
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val maxPixels = 4_000_000 // 4 megapixels max for Gemini
        val currentPixels = bitmap.width.toLong() * bitmap.height.toLong()

        return if (currentPixels > maxPixels) {
            val scale = kotlin.math.sqrt(maxPixels.toDouble() / currentPixels.toDouble())
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun serializeDiagnosis(diagnosis: DiagnosisDisplay): String {
        return gson.toJson(diagnosis)
    }

    fun parseSavedDiagnosis(sessionId: String, json: String?): DiagnosisDisplay? {
        if (json.isNullOrBlank()) return null

        return try {
            gson.fromJson(json, DiagnosisDisplay::class.java)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse saved diagnosis JSON")
            // Fallback for legacy format
            try {
                val isHealthy = json.contains("\"isHealthy\":true") || json.contains("\"is_healthy\":true")
                val healthScore = Regex("\"health[Ss]core\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val primaryDiagnosis = Regex("\"primary[Dd]iagnosis\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                val confidence = Regex("\"confidence\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                DiagnosisDisplay(
                    sessionId = sessionId,
                    isHealthy = isHealthy,
                    healthScore = healthScore,
                    primaryDiagnosis = primaryDiagnosis,
                    confidence = confidence,
                    diseases = emptyList(),
                    suggestions = emptyList(),
                    leafDescription = null,
                    analyzedAt = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse legacy diagnosis format")
                null
            }
        }
    }
}
