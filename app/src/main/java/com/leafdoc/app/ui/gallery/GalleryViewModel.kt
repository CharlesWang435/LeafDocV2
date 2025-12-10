package com.leafdoc.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leafdoc.app.data.model.DiagnosisStatus
import com.leafdoc.app.data.model.LeafSession
import com.leafdoc.app.data.repository.ImageRepository
import com.leafdoc.app.data.repository.LeafSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val sessionRepository: LeafSessionRepository,
    private val imageRepository: ImageRepository
) : ViewModel() {

    private val _filter = MutableStateFlow(GalleryFilter.ALL)
    val filter: StateFlow<GalleryFilter> = _filter.asStateFlow()

    val sessions: StateFlow<List<LeafSession>> = _filter.flatMapLatest { filter ->
        when (filter) {
            GalleryFilter.ALL -> sessionRepository.getAllSessions()
            GalleryFilter.COMPLETED -> sessionRepository.getCompletedSessions()
            GalleryFilter.PENDING_DIAGNOSIS -> sessionRepository.getSessionsByDiagnosisStatus(DiagnosisStatus.PENDING)
            GalleryFilter.DIAGNOSED -> sessionRepository.getSessionsByDiagnosisStatus(DiagnosisStatus.COMPLETED)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedSessions = MutableStateFlow<Set<String>>(emptySet())
    val selectedSessions: StateFlow<Set<String>> = _selectedSessions.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = _selectedSessions.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    fun setFilter(filter: GalleryFilter) {
        _filter.value = filter
    }

    fun toggleSessionSelection(sessionId: String) {
        _selectedSessions.update { selected ->
            if (sessionId in selected) {
                selected - sessionId
            } else {
                selected + sessionId
            }
        }
    }

    fun selectAll() {
        viewModelScope.launch {
            _selectedSessions.value = sessions.value.map { it.id }.toSet()
        }
    }

    fun clearSelection() {
        _selectedSessions.value = emptySet()
    }

    fun deleteSelectedSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }

            try {
                _selectedSessions.value.forEach { sessionId ->
                    sessionRepository.deleteSession(sessionId)
                    imageRepository.deleteSessionImages(sessionId)
                }
                _selectedSessions.value = emptySet()
                _uiState.update { it.copy(
                    isDeleting = false,
                    message = "Sessions deleted"
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isDeleting = false,
                    error = "Failed to delete: ${e.message}"
                )}
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                sessionRepository.deleteSession(sessionId)
                imageRepository.deleteSessionImages(sessionId)
                _uiState.update { it.copy(message = "Session deleted") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete: ${e.message}") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class GalleryUiState(
    val isDeleting: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

enum class GalleryFilter(val displayName: String) {
    ALL("All"),
    COMPLETED("Completed"),
    PENDING_DIAGNOSIS("Pending"),
    DIAGNOSED("Diagnosed")
}
