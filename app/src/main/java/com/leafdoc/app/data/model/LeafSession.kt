package com.leafdoc.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a complete leaf imaging session containing multiple stitched segments.
 */
@Entity(tableName = "leaf_sessions")
data class LeafSession(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val farmerId: String = "",
    val fieldId: String = "",
    val leafNumber: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val locationAccuracy: Float? = null,
    val segmentCount: Int = 0,
    val stitchedImagePath: String? = null,
    val notes: String = "",
    val diagnosisStatus: DiagnosisStatus = DiagnosisStatus.PENDING,
    val diagnosisResult: String? = null,
    val diagnosisConfidence: Float? = null,
    val isComplete: Boolean = false
)

enum class DiagnosisStatus {
    PENDING,
    UPLOADING,
    PROCESSING,
    COMPLETED,
    FAILED
}
