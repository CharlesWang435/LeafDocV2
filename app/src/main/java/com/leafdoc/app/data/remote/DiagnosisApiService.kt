package com.leafdoc.app.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface DiagnosisApiService {

    @Multipart
    @POST("health_assessment")
    suspend fun analyzeLeafHealth(
        @Part image: MultipartBody.Part,
        @Part("similar_images") similarImages: RequestBody? = null,
        @Part("latitude") latitude: RequestBody? = null,
        @Part("longitude") longitude: RequestBody? = null,
        @Part("health") health: RequestBody? = null
    ): Response<PlantIdResponse>

    @POST("health_assessment")
    suspend fun analyzeLeafHealthBase64(
        @Body request: PlantIdRequest
    ): Response<PlantIdResponse>
}

data class PlantIdRequest(
    val images: List<String>,  // Base64 encoded images
    val latitude: Double? = null,
    val longitude: Double? = null,
    val similar_images: Boolean = false,
    val health: String = "all"
)

data class PlantIdResponse(
    val access_token: String?,
    val model_version: String?,
    val custom_id: String?,
    val input: PlantIdInput?,
    val result: PlantIdResult?,
    val status: String?,
    val sla_compliant_client: Boolean?,
    val sla_compliant_system: Boolean?,
    val created: Double?,
    val completed: Double?
)

data class PlantIdInput(
    val latitude: Double?,
    val longitude: Double?,
    val similar_images: Boolean?,
    val health: String?,
    val images: List<String>?
)

data class PlantIdResult(
    val is_plant: PlantIdClassification?,
    val is_healthy: PlantIdClassification?,
    val disease: PlantIdDisease?
)

data class PlantIdClassification(
    val probability: Float?,
    val binary: Boolean?,
    val threshold: Float?
)

data class PlantIdDisease(
    val suggestions: List<PlantIdDiseaseSuggestion>?
)

data class PlantIdDiseaseSuggestion(
    val id: String?,
    val name: String?,
    val probability: Float?,
    val similar_images: List<PlantIdSimilarImage>?,
    val details: PlantIdDiseaseDetails?
)

data class PlantIdSimilarImage(
    val id: String?,
    val url: String?,
    val license_name: String?,
    val license_url: String?,
    val citation: String?,
    val similarity: Float?,
    val url_small: String?
)

data class PlantIdDiseaseDetails(
    val local_name: String?,
    val description: String?,
    val url: String?,
    val treatment: PlantIdTreatment?,
    val classification: List<String>?,
    val common_names: List<String>?,
    val cause: String?,
    val language: String?,
    val entity_id: String?
)

data class PlantIdTreatment(
    val chemical: List<String>?,
    val biological: List<String>?,
    val prevention: List<String>?
)
