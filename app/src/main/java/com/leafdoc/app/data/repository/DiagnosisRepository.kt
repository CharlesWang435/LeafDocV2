package com.leafdoc.app.data.repository

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import com.leafdoc.app.data.local.LeafSessionDao
import com.leafdoc.app.data.model.*
import com.leafdoc.app.data.remote.DiagnosisApiService
import com.leafdoc.app.data.remote.PlantIdRequest
import com.leafdoc.app.data.remote.PlantIdResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosisRepository @Inject constructor(
    private val apiService: DiagnosisApiService,
    private val sessionDao: LeafSessionDao,
    private val gson: Gson
) {
    suspend fun analyzeLeaf(
        sessionId: String,
        imagePath: String,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<DiagnosisDisplay> = withContext(Dispatchers.IO) {
        try {
            // Update status to uploading
            sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.UPLOADING, null, null)

            // Read and encode image
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, "Image file not found", null)
                return@withContext Result.failure(Exception("Image file not found"))
            }

            val base64Image = encodeImageToBase64(imageFile)

            // Update status to processing
            sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.PROCESSING, null, null)

            // Make API call
            val request = PlantIdRequest(
                images = listOf("data:image/jpeg;base64,$base64Image"),
                latitude = latitude,
                longitude = longitude,
                health = "all"
            )

            val response = apiService.analyzeLeafHealthBase64(request)

            if (response.isSuccessful && response.body() != null) {
                val plantIdResponse = response.body()!!
                val diagnosis = parseDiagnosisResponse(sessionId, plantIdResponse)

                // Update database with results
                sessionDao.updateDiagnosis(
                    sessionId = sessionId,
                    status = DiagnosisStatus.COMPLETED,
                    result = serializeDiagnosis(diagnosis),
                    confidence = diagnosis.confidence / 100f
                )

                Result.success(diagnosis)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Unknown error"
                sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, errorMsg, null)
                Result.failure(Exception("API error: ${response.code()} - $errorMsg"))
            }
        } catch (e: Exception) {
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

            val base64Image = encodeBitmapToBase64(bitmap)

            sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.PROCESSING, null, null)

            val request = PlantIdRequest(
                images = listOf("data:image/jpeg;base64,$base64Image"),
                latitude = latitude,
                longitude = longitude,
                health = "all"
            )

            val response = apiService.analyzeLeafHealthBase64(request)

            if (response.isSuccessful && response.body() != null) {
                val plantIdResponse = response.body()!!
                val diagnosis = parseDiagnosisResponse(sessionId, plantIdResponse)

                sessionDao.updateDiagnosis(
                    sessionId = sessionId,
                    status = DiagnosisStatus.COMPLETED,
                    result = serializeDiagnosis(diagnosis),
                    confidence = diagnosis.confidence / 100f
                )

                Result.success(diagnosis)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Unknown error"
                sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, errorMsg, null)
                Result.failure(Exception("API error: ${response.code()} - $errorMsg"))
            }
        } catch (e: Exception) {
            sessionDao.updateDiagnosis(sessionId, DiagnosisStatus.FAILED, e.message, null)
            Result.failure(e)
        }
    }

    private fun encodeImageToBase64(file: File): String {
        // Load and resize image if needed (Plant.id limit: 25 megapixels)
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)

        val maxPixels = 25_000_000 // 25 megapixels
        val currentPixels = options.outWidth.toLong() * options.outHeight.toLong()

        if (currentPixels > maxPixels) {
            // Calculate scale factor to fit within limit
            val scale = kotlin.math.sqrt(maxPixels.toDouble() / currentPixels.toDouble())
            val newWidth = (options.outWidth * scale).toInt()
            val newHeight = (options.outHeight * scale).toInt()

            // Load scaled bitmap
            val loadOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(options, newWidth, newHeight)
            }
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, loadOptions)

            // Scale to exact dimensions if needed
            val scaledBitmap = if (bitmap.width > newWidth || bitmap.height > newHeight) {
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            return encodeBitmapToBase64(scaledBitmap).also {
                scaledBitmap.recycle()
            }
        } else {
            val bytes = file.readBytes()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
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

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        // Check if bitmap needs resizing for API
        val maxPixels = 25_000_000
        val currentPixels = bitmap.width.toLong() * bitmap.height.toLong()

        val bitmapToEncode = if (currentPixels > maxPixels) {
            val scale = kotlin.math.sqrt(maxPixels.toDouble() / currentPixels.toDouble())
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        bitmapToEncode.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()

        if (bitmapToEncode != bitmap) {
            bitmapToEncode.recycle()
        }

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun parseDiagnosisResponse(sessionId: String, response: PlantIdResponse): DiagnosisDisplay {
        val result = response.result
        val isHealthy = result?.is_healthy?.binary ?: true
        val healthProbability = result?.is_healthy?.probability ?: 1f

        val diseases = result?.disease?.suggestions?.map { suggestion ->
            DiseaseInfo(
                name = suggestion.name ?: "Unknown",
                commonName = suggestion.details?.local_name ?: suggestion.details?.common_names?.firstOrNull(),
                probability = ((suggestion.probability ?: 0f) * 100).toInt(),
                description = suggestion.details?.description,
                treatments = buildList {
                    suggestion.details?.treatment?.chemical?.let { addAll(it) }
                    suggestion.details?.treatment?.biological?.let { addAll(it) }
                    suggestion.details?.treatment?.prevention?.let { addAll(it) }
                },
                severity = when {
                    (suggestion.probability ?: 0f) > 0.7f -> DiseaseSeverity.SEVERE
                    (suggestion.probability ?: 0f) > 0.5f -> DiseaseSeverity.HIGH
                    (suggestion.probability ?: 0f) > 0.3f -> DiseaseSeverity.MODERATE
                    else -> DiseaseSeverity.LOW
                }
            )
        } ?: emptyList()

        val suggestions = buildList {
            if (!isHealthy && diseases.isNotEmpty()) {
                add("Primary concern: ${diseases.first().name}")
                diseases.firstOrNull()?.treatments?.take(3)?.let { addAll(it) }
            }
            if (isHealthy) {
                add("The leaf appears healthy. Continue regular monitoring.")
            }
        }

        return DiagnosisDisplay(
            sessionId = sessionId,
            isHealthy = isHealthy,
            healthScore = (healthProbability * 100).toInt(),
            primaryDiagnosis = if (!isHealthy) diseases.firstOrNull()?.name else "Healthy",
            confidence = (healthProbability * 100).toInt(),
            diseases = diseases.sortedByDescending { it.probability },
            suggestions = suggestions,
            analyzedAt = System.currentTimeMillis()
        )
    }

    private fun serializeDiagnosis(diagnosis: DiagnosisDisplay): String {
        return gson.toJson(diagnosis)
    }

    fun parseSavedDiagnosis(sessionId: String, json: String?): DiagnosisDisplay? {
        if (json.isNullOrBlank()) return null

        return try {
            gson.fromJson(json, DiagnosisDisplay::class.java)
        } catch (e: Exception) {
            // Fallback for legacy format (manual JSON)
            try {
                val isHealthy = json.contains("\"isHealthy\":true")
                val healthScore = Regex("\"healthScore\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val primaryDiagnosis = Regex("\"primaryDiagnosis\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                val confidence = Regex("\"confidence\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                DiagnosisDisplay(
                    sessionId = sessionId,
                    isHealthy = isHealthy,
                    healthScore = healthScore,
                    primaryDiagnosis = primaryDiagnosis,
                    confidence = confidence,
                    diseases = emptyList(),
                    suggestions = emptyList(),
                    analyzedAt = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
