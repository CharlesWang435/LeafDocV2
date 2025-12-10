package com.leafdoc.app.data.local

import androidx.room.*
import com.leafdoc.app.data.model.LeafSegment
import kotlinx.coroutines.flow.Flow

@Dao
interface LeafSegmentDao {
    @Query("SELECT * FROM leaf_segments WHERE sessionId = :sessionId ORDER BY segmentIndex ASC")
    fun getSegmentsBySession(sessionId: String): Flow<List<LeafSegment>>

    @Query("SELECT * FROM leaf_segments WHERE sessionId = :sessionId ORDER BY segmentIndex ASC")
    suspend fun getSegmentsBySessionSync(sessionId: String): List<LeafSegment>

    @Query("SELECT * FROM leaf_segments WHERE id = :segmentId")
    suspend fun getSegmentById(segmentId: String): LeafSegment?

    @Query("SELECT * FROM leaf_segments WHERE sessionId = :sessionId ORDER BY segmentIndex DESC LIMIT 1")
    suspend fun getLastSegment(sessionId: String): LeafSegment?

    @Query("SELECT COUNT(*) FROM leaf_segments WHERE sessionId = :sessionId")
    suspend fun getSegmentCount(sessionId: String): Int

    @Query("SELECT MAX(segmentIndex) FROM leaf_segments WHERE sessionId = :sessionId")
    suspend fun getMaxSegmentIndex(sessionId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: LeafSegment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<LeafSegment>)

    @Update
    suspend fun updateSegment(segment: LeafSegment)

    @Delete
    suspend fun deleteSegment(segment: LeafSegment)

    @Query("DELETE FROM leaf_segments WHERE id = :segmentId")
    suspend fun deleteSegmentById(segmentId: String)

    @Query("DELETE FROM leaf_segments WHERE sessionId = :sessionId")
    suspend fun deleteSegmentsBySession(sessionId: String)

    @Query("SELECT imagePath FROM leaf_segments WHERE sessionId = :sessionId ORDER BY segmentIndex ASC")
    suspend fun getSegmentImagePaths(sessionId: String): List<String>
}
