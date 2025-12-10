package com.leafdoc.app.data.local

import androidx.room.*
import com.leafdoc.app.data.model.DiagnosisStatus
import com.leafdoc.app.data.model.LeafSession
import kotlinx.coroutines.flow.Flow

@Dao
interface LeafSessionDao {
    @Query("SELECT * FROM leaf_sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<LeafSession>>

    @Query("SELECT * FROM leaf_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): LeafSession?

    @Query("SELECT * FROM leaf_sessions WHERE id = :sessionId")
    fun getSessionByIdFlow(sessionId: String): Flow<LeafSession?>

    @Query("SELECT * FROM leaf_sessions WHERE farmerId = :farmerId ORDER BY createdAt DESC")
    fun getSessionsByFarmer(farmerId: String): Flow<List<LeafSession>>

    @Query("SELECT * FROM leaf_sessions WHERE diagnosisStatus = :status ORDER BY createdAt DESC")
    fun getSessionsByDiagnosisStatus(status: DiagnosisStatus): Flow<List<LeafSession>>

    @Query("SELECT * FROM leaf_sessions WHERE isComplete = 1 ORDER BY createdAt DESC")
    fun getCompletedSessions(): Flow<List<LeafSession>>

    @Query("SELECT * FROM leaf_sessions WHERE isComplete = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveSession(): LeafSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: LeafSession)

    @Update
    suspend fun updateSession(session: LeafSession)

    @Delete
    suspend fun deleteSession(session: LeafSession)

    @Query("DELETE FROM leaf_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("UPDATE leaf_sessions SET stitchedImagePath = :imagePath, isComplete = 1, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun completeSession(sessionId: String, imagePath: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE leaf_sessions SET diagnosisStatus = :status, diagnosisResult = :result, diagnosisConfidence = :confidence, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun updateDiagnosis(
        sessionId: String,
        status: DiagnosisStatus,
        result: String?,
        confidence: Float?,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE leaf_sessions SET segmentCount = segmentCount + 1, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun incrementSegmentCount(sessionId: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM leaf_sessions")
    suspend fun getTotalSessionCount(): Int

    @Query("SELECT COUNT(*) FROM leaf_sessions WHERE diagnosisStatus = 'COMPLETED'")
    suspend fun getAnalyzedSessionCount(): Int
}
