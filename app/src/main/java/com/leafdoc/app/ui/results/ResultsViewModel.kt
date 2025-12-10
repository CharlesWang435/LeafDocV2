package com.leafdoc.app.ui.results

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leafdoc.app.data.model.*
import com.leafdoc.app.data.preferences.UserPreferencesManager
import com.leafdoc.app.data.repository.DiagnosisRepository
import com.leafdoc.app.data.repository.ImageRepository
import com.leafdoc.app.data.repository.LeafSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ResultsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: LeafSessionRepository,
    private val imageRepository: ImageRepository,
    private val diagnosisRepository: DiagnosisRepository,
    private val preferencesManager: UserPreferencesManager
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>("sessionId") ?: ""

    val session: StateFlow<LeafSession?> = sessionRepository.getSessionByIdFlow(sessionId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val segments = sessionRepository.getSegmentsBySession(sessionId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val exportSettings = preferencesManager.exportSettings
        .stateIn(viewModelScope, SharingStarted.Lazily, ExportSettings())

    private val _uiState = MutableStateFlow(ResultsUiState())
    val uiState: StateFlow<ResultsUiState> = _uiState.asStateFlow()

    private val _diagnosis = MutableStateFlow<DiagnosisDisplay?>(null)
    val diagnosis: StateFlow<DiagnosisDisplay?> = _diagnosis.asStateFlow()

    init {
        loadDiagnosis()
    }

    private fun loadDiagnosis() {
        viewModelScope.launch {
            session.collect { sess ->
                if (sess != null && sess.diagnosisStatus == DiagnosisStatus.COMPLETED) {
                    _diagnosis.value = diagnosisRepository.parseSavedDiagnosis(
                        sessionId,
                        sess.diagnosisResult
                    )
                }
            }
        }
    }

    fun analyzeDiagnosis() {
        viewModelScope.launch {
            val sess = session.value ?: return@launch
            val imagePath = sess.stitchedImagePath ?: return@launch

            _uiState.update { it.copy(isAnalyzing = true, error = null) }

            val result = diagnosisRepository.analyzeLeaf(
                sessionId = sessionId,
                imagePath = imagePath,
                latitude = sess.latitude,
                longitude = sess.longitude
            )

            result.fold(
                onSuccess = { diagnosis ->
                    _diagnosis.value = diagnosis
                    _uiState.update { it.copy(
                        isAnalyzing = false,
                        message = "Analysis complete"
                    )}
                },
                onFailure = { error ->
                    _uiState.update { it.copy(
                        isAnalyzing = false,
                        error = "Analysis failed: ${error.message}"
                    )}
                }
            )
        }
    }

    fun exportImage(format: ImageFormat? = null) {
        viewModelScope.launch {
            val sess = session.value ?: return@launch
            val imagePath = sess.stitchedImagePath ?: return@launch

            _uiState.update { it.copy(isExporting = true, error = null) }

            try {
                val settings = if (format != null) {
                    exportSettings.value.copy(format = format)
                } else {
                    exportSettings.value
                }

                val fileName = buildExportFileName(sess, settings.format)
                val uri = imageRepository.exportImage(imagePath, settings, fileName)

                if (uri != null) {
                    _uiState.update { it.copy(
                        isExporting = false,
                        exportedUri = uri,
                        message = "Image exported successfully"
                    )}
                } else {
                    _uiState.update { it.copy(
                        isExporting = false,
                        error = "Failed to export image"
                    )}
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isExporting = false,
                    error = "Export failed: ${e.message}"
                )}
            }
        }
    }

    private fun buildExportFileName(session: LeafSession, format: ImageFormat): String {
        val parts = mutableListOf<String>()
        parts.add("LeafDoc")

        if (session.farmerId.isNotEmpty()) {
            parts.add(session.farmerId.take(20))
        }
        if (session.fieldId.isNotEmpty()) {
            parts.add(session.fieldId.take(20))
        }
        parts.add("Leaf${session.leafNumber}")

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date(session.createdAt))
        parts.add(timestamp)

        return "${parts.joinToString("_")}.${format.extension}"
    }

    fun shareImage() {
        viewModelScope.launch {
            val sess = session.value ?: return@launch
            val imagePath = sess.stitchedImagePath ?: return@launch

            try {
                val uri = imageRepository.getImageUri(imagePath)
                _uiState.update { it.copy(shareUri = uri) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to share: ${e.message}") }
            }
        }
    }

    fun updateExportFormat(format: ImageFormat) {
        viewModelScope.launch {
            preferencesManager.updateExportSettings(
                exportSettings.value.copy(format = format)
            )
        }
    }

    fun updateExportQuality(quality: Int) {
        viewModelScope.launch {
            preferencesManager.updateExportSettings(
                exportSettings.value.copy(quality = quality)
            )
        }
    }

    fun deleteSession() {
        viewModelScope.launch {
            try {
                sessionRepository.deleteSession(sessionId)
                imageRepository.deleteSessionImages(sessionId)
                _uiState.update { it.copy(sessionDeleted = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete: ${e.message}") }
            }
        }
    }

    fun updateNotes(notes: String) {
        viewModelScope.launch {
            session.value?.let { sess ->
                sessionRepository.updateSession(sess.copy(notes = notes))
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearShareUri() {
        _uiState.update { it.copy(shareUri = null) }
    }

    fun clearExportedUri() {
        _uiState.update { it.copy(exportedUri = null) }
    }
}

data class ResultsUiState(
    val isAnalyzing: Boolean = false,
    val isExporting: Boolean = false,
    val exportedUri: Uri? = null,
    val shareUri: Uri? = null,
    val sessionDeleted: Boolean = false,
    val message: String? = null,
    val error: String? = null
)
