package com.leafdoc.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leafdoc.app.data.model.*
import com.leafdoc.app.data.preferences.UserPreferencesManager
import com.leafdoc.app.data.repository.ImageRepository
import com.leafdoc.app.data.repository.StorageInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: UserPreferencesManager,
    private val imageRepository: ImageRepository
) : ViewModel() {

    val cameraSettings = preferencesManager.cameraSettings
        .stateIn(viewModelScope, SharingStarted.Lazily, CameraSettings())

    val exportSettings = preferencesManager.exportSettings
        .stateIn(viewModelScope, SharingStarted.Lazily, ExportSettings())

    val farmerId = preferencesManager.farmerId
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    val fieldId = preferencesManager.fieldId
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    val overlapPercentage = preferencesManager.overlapGuidePercentage
        .stateIn(viewModelScope, SharingStarted.Lazily, 10)

    val autoSaveSegments = preferencesManager.autoSaveSegments
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    val vibrateOnCapture = preferencesManager.vibrateOnCapture
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    val keepScreenOn = preferencesManager.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    // Midrib alignment settings
    val midribAlignmentEnabled = preferencesManager.midribAlignmentEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    val midribSearchTolerance = preferencesManager.midribSearchTolerance
        .stateIn(viewModelScope, SharingStarted.Lazily, 50)

    val midribGuideEnabled = preferencesManager.midribGuideEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo.asStateFlow()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadStorageInfo()
    }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            _storageInfo.value = imageRepository.getStorageUsage()
        }
    }

    // Camera Settings
    fun updateGridOverlay(type: GridOverlayType) {
        viewModelScope.launch {
            preferencesManager.updateCameraSettings(
                cameraSettings.value.copy(gridOverlay = type)
            )
        }
    }

    fun updateShowHistogram(show: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateCameraSettings(
                cameraSettings.value.copy(showHistogram = show)
            )
        }
    }

    fun updateShowFocusPeaking(show: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateCameraSettings(
                cameraSettings.value.copy(showFocusPeaking = show)
            )
        }
    }

    fun updateShowZebras(show: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateCameraSettings(
                cameraSettings.value.copy(showZebras = show)
            )
        }
    }

    fun updateZebraThreshold(threshold: Int) {
        viewModelScope.launch {
            preferencesManager.updateCameraSettings(
                cameraSettings.value.copy(zebraThreshold = threshold)
            )
        }
    }

    fun updateResolution(mode: ResolutionMode) {
        viewModelScope.launch {
            preferencesManager.updateCameraSettings(
                cameraSettings.value.copy(resolution = mode)
            )
        }
    }

    // Export Settings
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

    fun updateIncludeMetadata(include: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateExportSettings(
                exportSettings.value.copy(includeMetadata = include)
            )
        }
    }

    fun updateExportLocation(location: ExportLocation) {
        viewModelScope.launch {
            preferencesManager.updateExportSettings(
                exportSettings.value.copy(exportLocation = location)
            )
        }
    }

    // User Settings
    fun updateFarmerId(id: String) {
        viewModelScope.launch {
            preferencesManager.updateFarmerId(id)
        }
    }

    fun updateFieldId(id: String) {
        viewModelScope.launch {
            preferencesManager.updateFieldId(id)
        }
    }

    // App Settings
    fun updateOverlapPercentage(percentage: Int) {
        viewModelScope.launch {
            preferencesManager.updateOverlapGuidePercentage(percentage)
        }
    }

    fun updateAutoSaveSegments(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateAutoSaveSegments(enabled)
        }
    }

    fun updateVibrateOnCapture(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateVibrateOnCapture(enabled)
        }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateKeepScreenOn(enabled)
        }
    }

    // Midrib Alignment Settings
    fun updateMidribAlignmentEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateMidribAlignmentEnabled(enabled)
        }
    }

    fun updateMidribSearchTolerance(tolerance: Int) {
        viewModelScope.launch {
            preferencesManager.updateMidribSearchTolerance(tolerance)
        }
    }

    fun updateMidribGuideEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.updateMidribGuideEnabled(enabled)
        }
    }

    // Storage Management
    fun clearThumbnailCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingCache = true) }
            try {
                imageRepository.clearThumbnailCache()
                loadStorageInfo()
                _uiState.update { it.copy(
                    isClearingCache = false,
                    message = "Cache cleared"
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isClearingCache = false,
                    error = "Failed to clear cache"
                )}
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

data class SettingsUiState(
    val isClearingCache: Boolean = false,
    val message: String? = null,
    val error: String? = null
)
