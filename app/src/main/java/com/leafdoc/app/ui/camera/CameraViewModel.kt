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

    // Midrib alignment settings
    val midribAlignmentEnabled: StateFlow<Boolean> = preferencesManager.midribAlignmentEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val midribSearchTolerance: StateFlow<Int> = preferencesManager.midribSearchTolerance
        .stateIn(viewModelScope, SharingStarted.Eagerly, 50)

    val midribGuideEnabled: StateFlow<Boolean> = preferencesManager.midribGuideEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _midribGuidePosition = MutableStateFlow(0.5f)
    val midribGuidePosition: StateFlow<Float> = _midribGuidePosition.asStateFlow()

    private val _midribGuideThickness = MutableStateFlow(0.05f)
    val midribGuideThickness: StateFlow<Float> = _midribGuideThickness.asStateFlow()

    private val _midribGuideLocked = MutableStateFlow(false)
    val midribGuideLocked: StateFlow<Boolean> = _midribGuideLocked.asStateFlow()

    private val _cropRectLocked = MutableStateFlow(false)
    val cropRectLocked: StateFlow<Boolean> = _cropRectLocked.asStateFlow()

    // Preview dimensions for accurate crop mapping
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0

    init {
        loadSettings()
        observeLocation()
        loadMidribSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            preferencesManager.cameraSettings.collect { settings ->
                _cameraSettings.value = settings
            }
        }
    }

    private fun loadMidribSettings() {
        viewModelScope.launch {
            preferencesManager.midribGuidePosition.collect { position ->
                _midribGuidePosition.value = position
            }
        }
        viewModelScope.launch {
            preferencesManager.midribGuideThickness.collect { thickness ->
                _midribGuideThickness.value = thickness
            }
        }
        viewModelScope.launch {
            preferencesManager.midribGuideLocked.collect { locked ->
                _midribGuideLocked.value = locked
            }
        }
        viewModelScope.launch {
            preferencesManager.cropRectLocked.collect { locked ->
                _cropRectLocked.value = locked
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
     *
     * The crop rectangle is defined as fractions (0-1) of the preview view.
     * Since the preview uses FILL_CENTER scaling, parts of the camera image
     * may be cropped to fill the screen. We need to account for this when
     * mapping the crop rectangle to the actual captured image.
     *
     * With FILL_CENTER:
     * - If image is wider than screen: sides of image are cropped, full height visible
     * - If image is taller than screen: top/bottom of image are cropped, full width visible
     */
    private fun cropBitmap(source: Bitmap, cropRect: CropRect): Bitmap {
        // The captured image dimensions
        val imageWidth = source.width.toFloat()
        val imageHeight = source.height.toFloat()
        val imageAspect = imageWidth / imageHeight

        // Use actual preview dimensions if available, otherwise use typical portrait aspect
        val screenAspect = if (previewWidth > 0 && previewHeight > 0) {
            previewWidth.toFloat() / previewHeight.toFloat()
        } else {
            9f / 16f // Fallback: typical portrait phone
        }

        // Calculate what portion of the image is visible in FILL_CENTER mode
        val visibleLeft: Float
        val visibleTop: Float
        val visibleWidth: Float
        val visibleHeight: Float

        if (imageAspect > screenAspect) {
            // Image is wider than screen - sides are cropped
            visibleHeight = imageHeight
            visibleWidth = imageHeight * screenAspect
            visibleLeft = (imageWidth - visibleWidth) / 2f
            visibleTop = 0f
        } else {
            // Image is taller than screen - top/bottom are cropped
            visibleWidth = imageWidth
            visibleHeight = imageWidth / screenAspect
            visibleLeft = 0f
            visibleTop = (imageHeight - visibleHeight) / 2f
        }

        // Map the crop rectangle from preview coordinates to image coordinates
        val left = (visibleLeft + visibleWidth * cropRect.left).toInt().coerceIn(0, source.width - 1)
        val top = (visibleTop + visibleHeight * cropRect.top).toInt().coerceIn(0, source.height - 1)
        val right = (visibleLeft + visibleWidth * cropRect.right).toInt().coerceIn(left + 1, source.width)
        val bottom = (visibleTop + visibleHeight * cropRect.bottom).toInt().coerceIn(top + 1, source.height)

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

                // Stitch images using simple stitcher with optional midrib alignment
                val overlapPercent = overlapPercentage.value / 100f
                val alignMidrib = midribAlignmentEnabled.value
                val searchTolerance = midribSearchTolerance.value / 100f
                val stitchResult = simpleStitcher.stitchImages(
                    images = bitmaps,
                    overlapPercent = overlapPercent,
                    alignMidrib = alignMidrib,
                    midribSearchTolerance = searchTolerance
                )

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
        _capturedSegments.value = emptyList()
        currentSession = null
        _uiState.update { it.copy(
            sessionActive = false,
            sessionId = null,
            sessionComplete = false,
            stitchedImagePath = null,
            lastCapturedThumbnail = null,
            segmentCount = 0,
            message = null
        )}
    }

    fun updateCropRect(cropRect: CropRect) {
        _cropRect.value = cropRect
    }

    fun updatePreviewDimensions(width: Int, height: Int) {
        previewWidth = width
        previewHeight = height
    }

    fun updateMidribGuidePosition(position: Float) {
        _midribGuidePosition.value = position.coerceIn(0f, 1f)
        viewModelScope.launch {
            preferencesManager.updateMidribGuidePosition(position)
        }
    }

    fun updateMidribGuideThickness(thickness: Float) {
        _midribGuideThickness.value = thickness.coerceIn(0.02f, 0.15f)
        viewModelScope.launch {
            preferencesManager.updateMidribGuideThickness(thickness)
        }
    }

    fun toggleMidribGuideLocked() {
        val newLocked = !_midribGuideLocked.value
        _midribGuideLocked.value = newLocked
        viewModelScope.launch {
            preferencesManager.updateMidribGuideLocked(newLocked)
        }
    }

    fun toggleCropRectLocked() {
        val newLocked = !_cropRectLocked.value
        _cropRectLocked.value = newLocked
        viewModelScope.launch {
            preferencesManager.updateCropRectLocked(newLocked)
        }
    }

    fun toggleMidribGuideEnabled() {
        viewModelScope.launch {
            val current = midribGuideEnabled.value
            preferencesManager.updateMidribGuideEnabled(!current)
        }
    }

    fun toggleMidribAlignment() {
        viewModelScope.launch {
            val current = midribAlignmentEnabled.value
            preferencesManager.updateMidribAlignmentEnabled(!current)
        }
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
