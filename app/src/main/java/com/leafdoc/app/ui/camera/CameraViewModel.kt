package com.leafdoc.app.ui.camera

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leafdoc.app.camera.CapturedImage
import com.leafdoc.app.camera.CameraState
import android.graphics.Rect as AndroidRect
import com.leafdoc.app.data.model.*
import com.leafdoc.app.data.preferences.UserPreferencesManager
import com.leafdoc.app.data.repository.ImageRepository
import com.leafdoc.app.data.repository.LeafSessionRepository
import com.leafdoc.app.stitching.SimpleStitcher
import com.leafdoc.app.stitching.StitchResult
import com.leafdoc.app.util.LocationData
import com.leafdoc.app.util.LocationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val sessionRepository: LeafSessionRepository,
    private val imageRepository: ImageRepository,
    private val preferencesManager: UserPreferencesManager,
    private val locationManager: LocationManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _cameraSettings = MutableStateFlow(CameraSettings())
    val cameraSettings: StateFlow<CameraSettings> = _cameraSettings.asStateFlow()

    private val _capturedSegments = MutableStateFlow<List<CapturedSegmentInfo>>(emptyList())
    val capturedSegments: StateFlow<List<CapturedSegmentInfo>> = _capturedSegments.asStateFlow()

    private val _cropRect = MutableStateFlow(CropRect())
    val cropRect: StateFlow<CropRect> = _cropRect.asStateFlow()

    private val simpleStitcher = SimpleStitcher()

    private var currentSession: LeafSession? = null
    private var currentLocation: LocationData? = null

    val overlapPercentage: StateFlow<Int> = preferencesManager.overlapGuidePercentage
        .stateIn(viewModelScope, SharingStarted.Eagerly, 10)

    val defaultFarmerId: StateFlow<String> = preferencesManager.farmerId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val defaultFieldId: StateFlow<String> = preferencesManager.fieldId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        loadSettings()
        observeLocation()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            preferencesManager.cameraSettings.collect { settings ->
                _cameraSettings.value = settings
            }
        }
    }

    private fun observeLocation() {
        viewModelScope.launch {
            try {
                currentLocation = locationManager.getCurrentLocation()
            } catch (e: Exception) {
                // Location not available
            }
        }
    }

    fun startNewSession(farmerId: String = "", fieldId: String = "", leafNumber: Int = 1) {
        viewModelScope.launch {
            try {
                currentSession = sessionRepository.createSession(
                    farmerId = farmerId,
                    fieldId = fieldId,
                    leafNumber = leafNumber,
                    latitude = currentLocation?.latitude,
                    longitude = currentLocation?.longitude,
                    altitude = currentLocation?.altitude,
                    locationAccuracy = currentLocation?.accuracy
                )
                _capturedSegments.value = emptyList()

                _uiState.update { it.copy(
                    sessionActive = true,
                    sessionId = currentSession?.id,
                    segmentCount = 0,
                    message = "Session started. Capture first segment."
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    error = "Failed to start session: ${e.message}"
                )}
            }
        }
    }

    fun onImageCaptured(capturedImage: CapturedImage) {
        viewModelScope.launch {
            val session = currentSession ?: run {
                _uiState.update { it.copy(error = "No active session") }
                return@launch
            }

            _uiState.update { it.copy(isProcessing = true, message = "Saving segment...") }

            var imagePath: String? = null
            var thumbnailPath: String? = null
            var croppedBitmap: Bitmap? = null

            try {
                val segmentIndex = _capturedSegments.value.size

                // Crop the captured image according to the crop rectangle
                val currentCropRect = _cropRect.value
                croppedBitmap = cropBitmap(capturedImage.bitmap, currentCropRect)

                // Save cropped image to storage
                imagePath = imageRepository.saveSegmentImage(
                    bitmap = croppedBitmap,
                    sessionId = session.id,
                    segmentIndex = segmentIndex
                )

                // Create thumbnail
                thumbnailPath = imageRepository.createThumbnail(imagePath)

                // Use fixed overlap percentage from settings (no auto-calculation)
                val overlap = overlapPercentage.value / 100f

                // Add segment to database
                val segment = sessionRepository.addSegment(
                    sessionId = session.id,
                    imagePath = imagePath,
                    thumbnailPath = thumbnailPath,
                    width = croppedBitmap.width,
                    height = croppedBitmap.height,
                    iso = capturedImage.iso,
                    shutterSpeed = capturedImage.shutterSpeed,
                    focusDistance = capturedImage.focusDistance,
                    whiteBalance = capturedImage.whiteBalance.temperature,
                    exposureCompensation = capturedImage.exposureCompensation,
                    overlapPercentage = overlap
                )

                // Update captured segments list
                val segmentInfo = CapturedSegmentInfo(
                    id = segment.id,
                    index = segmentIndex,
                    imagePath = imagePath,
                    thumbnailPath = thumbnailPath,
                    timestamp = capturedImage.timestamp
                )
                _capturedSegments.update { it + segmentInfo }

                _uiState.update { it.copy(
                    isProcessing = false,
                    segmentCount = _capturedSegments.value.size,
                    message = "Segment ${segmentIndex + 1} saved. Slide leaf and capture next.",
                    lastCapturedThumbnail = thumbnailPath
                )}

            } catch (e: Exception) {
                // Clean up orphaned files on failure
                imagePath?.let { path ->
                    try { imageRepository.deleteImage(path) } catch (_: Exception) {}
                }
                thumbnailPath?.let { path ->
                    try { imageRepository.deleteImage(path) } catch (_: Exception) {}
                }

                _uiState.update { it.copy(
                    isProcessing = false,
                    error = "Failed to save segment: ${e.message}"
                )}
            } finally {
                croppedBitmap?.recycle()
            }
        }
    }

    /**
     * Crops a bitmap according to the given crop rectangle.
     */
    private fun cropBitmap(source: Bitmap, cropRect: CropRect): Bitmap {
        val left = (source.width * cropRect.left).toInt().coerceIn(0, source.width - 1)
        val top = (source.height * cropRect.top).toInt().coerceIn(0, source.height - 1)
        val right = (source.width * cropRect.right).toInt().coerceIn(left + 1, source.width)
        val bottom = (source.height * cropRect.bottom).toInt().coerceIn(top + 1, source.height)

        val width = right - left
        val height = bottom - top

        return Bitmap.createBitmap(source, left, top, width, height)
    }

    fun finishSession() {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val segments = _capturedSegments.value

            if (segments.isEmpty()) {
                _uiState.update { it.copy(error = "No segments captured") }
                return@launch
            }

            _uiState.update { it.copy(
                isProcessing = true,
                isStitching = true,
                message = "Stitching ${segments.size} segments..."
            )}

            try {
                // Load all segment images
                val bitmaps = segments.mapNotNull { segment ->
                    imageRepository.loadBitmap(segment.imagePath)
                }

                if (bitmaps.isEmpty()) {
                    throw Exception("Failed to load segment images")
                }

                // Stitch images using simple stitcher
                val overlapPercent = overlapPercentage.value / 100f
                val stitchResult = simpleStitcher.stitchImages(bitmaps, overlapPercent)

                // Recycle loaded bitmaps
                bitmaps.forEach { it.recycle() }

                when (stitchResult) {
                    is StitchResult.Success -> {
                        // Save stitched image
                        val stitchedPath = imageRepository.saveStitchedImage(
                            bitmap = stitchResult.bitmap,
                            sessionId = session.id
                        )

                        // Complete session
                        sessionRepository.completeSession(session.id, stitchedPath)

                        _uiState.update { it.copy(
                            isProcessing = false,
                            isStitching = false,
                            sessionActive = false,
                            sessionComplete = true,
                            stitchedImagePath = stitchedPath,
                            message = "Leaf image created successfully!"
                        )}

                        stitchResult.bitmap.recycle()
                    }
                    is StitchResult.Error -> {
                        _uiState.update { it.copy(
                            isProcessing = false,
                            isStitching = false,
                            error = "Stitching failed: ${stitchResult.message}"
                        )}
                    }
                    is StitchResult.Progress -> {
                        // Handle progress updates if needed
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isProcessing = false,
                    isStitching = false,
                    error = "Failed to complete session: ${e.message}"
                )}
            }
        }
    }

    fun cancelSession() {
        viewModelScope.launch {
            currentSession?.let { session ->
                sessionRepository.deleteSession(session.id)
                imageRepository.deleteSessionImages(session.id)
            }

            currentSession = null
            _capturedSegments.value = emptyList()

            _uiState.update { it.copy(
                sessionActive = false,
                segmentCount = 0,
                message = null
            )}
        }
    }

    fun deleteLastSegment() {
        viewModelScope.launch {
            val segments = _capturedSegments.value
            if (segments.isEmpty()) return@launch

            val lastSegment = segments.last()

            try {
                sessionRepository.deleteSegment(lastSegment.id)
                imageRepository.deleteImage(lastSegment.imagePath)
                lastSegment.thumbnailPath?.let { imageRepository.deleteImage(it) }

                _capturedSegments.update { it.dropLast(1) }

                _uiState.update { it.copy(
                    segmentCount = _capturedSegments.value.size,
                    message = "Segment deleted"
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete segment") }
            }
        }
    }

    fun updateCameraSettings(settings: CameraSettings) {
        viewModelScope.launch {
            _cameraSettings.value = settings
            preferencesManager.updateCameraSettings(settings)
        }
    }

    fun updateIso(iso: Int) {
        updateCameraSettings(_cameraSettings.value.copy(iso = iso))
    }

    fun updateShutterSpeed(shutterSpeed: Long) {
        updateCameraSettings(_cameraSettings.value.copy(shutterSpeed = shutterSpeed))
    }

    fun updateFocusDistance(distance: Float) {
        updateCameraSettings(_cameraSettings.value.copy(focusDistance = distance))
    }

    fun updateWhiteBalance(mode: WhiteBalanceMode) {
        updateCameraSettings(_cameraSettings.value.copy(whiteBalance = mode))
    }

    fun updateExposureCompensation(ev: Float) {
        updateCameraSettings(_cameraSettings.value.copy(exposureCompensation = ev))
    }

    fun updateFlashMode(mode: FlashMode) {
        updateCameraSettings(_cameraSettings.value.copy(flashMode = mode))
    }

    fun updateGridOverlay(type: GridOverlayType) {
        updateCameraSettings(_cameraSettings.value.copy(gridOverlay = type))
    }

    fun toggleHistogram() {
        updateCameraSettings(_cameraSettings.value.copy(
            showHistogram = !_cameraSettings.value.showHistogram
        ))
    }

    fun toggleFocusPeaking() {
        updateCameraSettings(_cameraSettings.value.copy(
            showFocusPeaking = !_cameraSettings.value.showFocusPeaking
        ))
    }

    fun toggleZebras() {
        updateCameraSettings(_cameraSettings.value.copy(
            showZebras = !_cameraSettings.value.showZebras
        ))
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun resetForNewCapture() {
        _uiState.update { it.copy(
            sessionComplete = false,
            stitchedImagePath = null
        )}
    }

    fun updateCropRect(cropRect: CropRect) {
        _cropRect.value = cropRect
    }
}

data class CameraUiState(
    val sessionActive: Boolean = false,
    val sessionId: String? = null,
    val segmentCount: Int = 0,
    val isProcessing: Boolean = false,
    val isStitching: Boolean = false,
    val sessionComplete: Boolean = false,
    val stitchedImagePath: String? = null,
    val lastCapturedThumbnail: String? = null,
    val message: String? = null,
    val error: String? = null
)

data class CapturedSegmentInfo(
    val id: String,
    val index: Int,
    val imagePath: String,
    val thumbnailPath: String?,
    val timestamp: Long
)
