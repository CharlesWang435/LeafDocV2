package com.leafdoc.app.data.repository

import com.leafdoc.app.data.local.LeafSegmentDao
import com.leafdoc.app.data.local.LeafSessionDao
import com.leafdoc.app.data.model.DiagnosisStatus
import com.leafdoc.app.data.model.LeafSegment
import com.leafdoc.app.data.model.LeafSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LeafSessionRepository @Inject constructor(
    private val sessionDao: LeafSessionDao,
    private val segmentDao: LeafSegmentDao
) {
    // Session operations
    fun getAllSessions(): Flow<List<LeafSession>> = sessionDao.getAllSessions()

    fun getSessionByIdFlow(sessionId: String): Flow<LeafSession?> =
        sessionDao.getSessionByIdFlow(sessionId)

    suspend fun getSessionById(sessionId: String): LeafSession? =
        sessionDao.getSessionById(sessionId)

    suspend fun getActiveSession(): LeafSession? = sessionDao.getActiveSession()

    fun getCompletedSessions(): Flow<List<LeafSession>> = sessionDao.getCompletedSessions()

    fun getSessionsByDiagnosisStatus(status: DiagnosisStatus): Flow<List<LeafSession>> =
        sessionDao.getSessionsByDiagnosisStatus(status)

    suspend fun createSession(
        farmerId: String = "",
        fieldId: String = "",
        leafNumber: Int = 1,
        latitude: Double? = null,
        longitude: Double? = null,
        altitude: Double? = null,
        locationAccuracy: Float? = null
    ): LeafSession {
        val session = LeafSession(
            farmerId = farmerId,
            fieldId = fieldId,
            leafNumber = leafNumber,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            locationAccuracy = locationAccuracy
        )
        sessionDao.insertSession(session)
        return session
    }

    suspend fun updateSession(session: LeafSession) {
        sessionDao.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteSession(sessionId: String) {
        sessionDao.deleteSessionById(sessionId)
    }

    suspend fun completeSession(sessionId: String, stitchedImagePath: String) {
        sessionDao.completeSession(sessionId, stitchedImagePath)
    }

    suspend fun updateDiagnosis(
        sessionId: String,
        status: DiagnosisStatus,
        result: String? = null,
        confidence: Float? = null
    ) {
        sessionDao.updateDiagnosis(sessionId, status, result, confidence)
    }

    // Segment operations
    fun getSegmentsBySession(sessionId: String): Flow<List<LeafSegment>> =
        segmentDao.getSegmentsBySession(sessionId)

    suspend fun getSegmentsBySessionSync(sessionId: String): List<LeafSegment> =
        segmentDao.getSegmentsBySessionSync(sessionId)

    suspend fun getSegmentImagePaths(sessionId: String): List<String> =
        segmentDao.getSegmentImagePaths(sessionId)

    suspend fun getLastSegment(sessionId: String): LeafSegment? =
        segmentDao.getLastSegment(sessionId)

    suspend fun getSegmentCount(sessionId: String): Int =
        segmentDao.getSegmentCount(sessionId)

    suspend fun addSegment(
        sessionId: String,
        imagePath: String,
        thumbnailPath: String? = null,
        width: Int = 0,
        height: Int = 0,
        iso: Int? = null,
        shutterSpeed: Long? = null,
        aperture: Float? = null,
        focalLength: Float? = null,
        whiteBalance: Int? = null,
        exposureCompensation: Float? = null,
        focusDistance: Float? = null,
        overlapPercentage: Float = 0f
    ): LeafSegment {
        val nextIndex = (segmentDao.getMaxSegmentIndex(sessionId) ?: -1) + 1

        val segment = LeafSegment(
            sessionId = sessionId,
            segmentIndex = nextIndex,
            imagePath = imagePath,
            thumbnailPath = thumbnailPath,
            width = width,
            height = height,
            iso = iso,
            shutterSpeed = shutterSpeed,
            aperture = aperture,
            focalLength = focalLength,
            whiteBalance = whiteBalance,
            exposureCompensation = exposureCompensation,
            focusDistance = focusDistance,
            overlapPercentage = overlapPercentage
        )

        segmentDao.insertSegment(segment)
        sessionDao.incrementSegmentCount(sessionId)

        return segment
    }

    suspend fun deleteSegment(segmentId: String) {
        segmentDao.deleteSegmentById(segmentId)
    }

    suspend fun deleteAllSegments(sessionId: String) {
        segmentDao.deleteSegmentsBySession(sessionId)
    }

    // Statistics
    suspend fun getTotalSessionCount(): Int = sessionDao.getTotalSessionCount()

    suspend fun getAnalyzedSessionCount(): Int = sessionDao.getAnalyzedSessionCount()
}
