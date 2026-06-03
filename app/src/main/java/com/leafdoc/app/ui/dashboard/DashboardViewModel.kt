package com.leafdoc.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leafdoc.app.data.model.DiagnosisStatus
import com.leafdoc.app.data.model.LeafSession
import com.leafdoc.app.data.repository.DiagnosisRepository
import com.leafdoc.app.data.repository.ImageRepository
import com.leafdoc.app.data.repository.LeafSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

/**
 * Aggregate overview of all captured sessions — derived entirely from data already in the
 * database (no new sources). Recomputes whenever the session list changes.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val sessionRepository: LeafSessionRepository,
    private val imageRepository: ImageRepository,
    private val diagnosisRepository: DiagnosisRepository
) : ViewModel() {

    private val _stats = MutableStateFlow(DashboardStats())
    val stats: StateFlow<DashboardStats> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            sessionRepository.getAllSessions().collect { sessions ->
                _stats.value = computeStats(sessions)
            }
        }
    }

    private suspend fun computeStats(sessions: List<LeafSession>): DashboardStats =
        withContext(Dispatchers.Default) {
            val startOfToday = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            var healthy = 0
            var issues = 0
            sessions.filter { it.diagnosisStatus == DiagnosisStatus.COMPLETED }.forEach { s ->
                val d = diagnosisRepository.parseSavedDiagnosis(s.id, s.diagnosisResult)
                if (d != null) {
                    if (d.isHealthy) healthy++ else issues++
                }
            }

            val storage = imageRepository.getStorageUsage()

            DashboardStats(
                totalSessions = sessions.size,
                capturedToday = sessions.count { it.createdAt >= startOfToday },
                totalFrames = sessions.sumOf { it.segmentCount },
                diagnosed = sessions.count { it.diagnosisStatus == DiagnosisStatus.COMPLETED },
                pendingDiagnosis = sessions.count { it.diagnosisStatus == DiagnosisStatus.PENDING },
                failedDiagnosis = sessions.count { it.diagnosisStatus == DiagnosisStatus.FAILED },
                healthy = healthy,
                issues = issues,
                uniqueFields = sessions.map { it.fieldId }.filter { it.isNotBlank() }.distinct().size,
                storageBytes = storage.totalBytes
            )
        }
}

data class DashboardStats(
    val totalSessions: Int = 0,
    val capturedToday: Int = 0,
    val totalFrames: Int = 0,
    val diagnosed: Int = 0,
    val pendingDiagnosis: Int = 0,
    val failedDiagnosis: Int = 0,
    val healthy: Int = 0,
    val issues: Int = 0,
    val uniqueFields: Int = 0,
    val storageBytes: Long = 0L
)
