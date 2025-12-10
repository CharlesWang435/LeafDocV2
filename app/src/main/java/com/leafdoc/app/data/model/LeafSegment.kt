package com.leafdoc.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a single captured segment of a leaf before stitching.
 */
@Entity(
    tableName = "leaf_segments",
    foreignKeys = [
        ForeignKey(
            entity = LeafSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class LeafSegment(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val segmentIndex: Int,
    val imagePath: String,
    val thumbnailPath: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
    val width: Int = 0,
    val height: Int = 0,
    val iso: Int? = null,
    val shutterSpeed: Long? = null,  // in nanoseconds
    val aperture: Float? = null,
    val focalLength: Float? = null,
    val whiteBalance: Int? = null,
    val exposureCompensation: Float? = null,
    val focusDistance: Float? = null,
    val overlapPercentage: Float = 0f  // Calculated overlap with previous segment
)
