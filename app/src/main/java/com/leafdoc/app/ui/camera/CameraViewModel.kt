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
import com.leafdoc.app.stitching.MidribAligner
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
    private val midribAligner = MidribAligner()

    private var currentSession: LeafSession? = null
    private var currentLocation: LocationData? = null

    // Manual alignment state
    private val _showManualAlignment = MutableStateFlow(false)
    val showManualAlignment: StateFlow<Boolean> = _showManualAlignment.asStateFlow()

    private val _alignmentBitmaps = MutableStateFlow<List<Bitmap>>(emptyList())
    val alignmentBitmaps: StateFlow<List<Bitmap>> = _alignmentBitmaps.asStateFlow()

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

    private val _cropRectEnabled = MutableStateFlow(true)
    val cropRectEnabled: StateFlow<Boolean> = _cropRectEnabled.asStateFlow()

    // ---- Simple (fast field) capture mode ----
    private val _simpleMode = MutableStateFlow(false)
    val simpleMode: StateFlow<Boolean> = _simpleMode.asStateFlow()

    private val _simpleFarmerId = MutableStateFlow("")
    val simpleFarmerId: StateFlow<String> = _simpleFarmerId.asStateFlow()

    private val _simpleFieldId = MutableStateFlow("")
    val simpleFieldId: StateFlow<String> = _simpleFieldId.asStateFlow()

    private val _simpleTreatment = MutableStateFlow("")
    val simpleTreatment: StateFlow<String> = _simpleTreatment.asStateFlow()

    private val _simpleLeafNumber = MutableStateFlow(1)
    val simpleLeafNumber: StateFlow<Int> = _simpleLeafNumber.asStateFlow()

    // User-managed pick lists for capture metadata
    val farmerIdOptions: StateFlow<List<String>> = preferencesManager.farmerIdOptions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val fieldIdOptions: StateFlow<List<String>> = preferencesManager.fieldIdOptions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val treatmentOptions: StateFlow<List<String>> = preferencesManager.treatmentOptions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // The captured image awaiting Retake/Export review in Simple mode.
    private val _pendingCapture = MutableStateFlow<CapturedImage?>(null)
    val pendingCapture: StateFlow<CapturedImage?> = _pendingCapture.asStateFlow()

    private val exportSettings: StateFlow<ExportSettings> = preferencesManager.exportSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExportSettings())

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
        viewModelScope.launch {
            preferencesManager.cropRectEnabled.collect { enabled ->
                _cropRectEnabled.value = enabled
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

    fun startNewSession(farmerId: String = "", fieldId: String = "", treatment: String = "", leafNumber: Int = 1) {
        viewModelScope.launch {
            try {
                currentSession = sessionRepository.createSession(
                    farmerId = farmerId,
                    fieldId = fieldId,
                    treatment = treatment,
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
                val segWidth: Int
                val segHeight: Int

                if (capturedImage.filePath != null) {
                    // High-res (full-sensor) capture: the JPEG is already on disk. Move it in
                    // as-is — no decode, no re-encode. Crop is NOT applied at max resolution
                    // (we keep the full-frame max-res image); use a standard size to crop.
                    imagePath = imageRepository.saveSegmentFile(
                        srcPath = capturedImage.filePath,
                        sessionId = session.id,
                        segmentIndex = segmentIndex
                    )
                    // Coil downsamples the full JPEG and honors EXIF orientation, so we reuse it
                    // as its own thumbnail rather than re-encoding a (sideways) thumbnail.
                    thumbnailPath = imagePath
                    segWidth = capturedImage.width
                    segHeight = capturedImage.height

                    // RAW master: save the .dng to the shared gallery for off-device use.
                    capturedImage.dngFilePath?.let { dngPath ->
                        val ts = System.currentTimeMillis()
                        val parts = mutableListOf("LeafDoc")
                        if (session.farmerId.isNotEmpty()) parts.add(session.farmerId.take(20))
                        if (session.fieldId.isNotEmpty()) parts.add(session.fieldId.take(20))
                        parts.add("Leaf${session.leafNumber}")
                        parts.add("seg${segmentIndex + 1}")
                        parts.add(ts.toString())
                        imageRepository.saveRawToGallery(dngPath, "${parts.joinToString("_")}.dng")
                    }
                } else {
                    val sourceBitmap = capturedImage.bitmap
                        ?: throw Exception("Captured image has no bitmap")

                    // Crop the captured image according to the crop rectangle (if enabled)
                    croppedBitmap = if (_cropRectEnabled.value) {
                        cropBitmap(sourceBitmap, _cropRect.value)
                    } else {
                        sourceBitmap.copy(sourceBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                    }

                    // Save in the user-selected capture format
                    val captureFormat = _cameraSettings.value.captureFormat
                    imagePath = imageRepository.saveSegmentImage(
                        bitmap = croppedBitmap,
                        sessionId = session.id,
                        segmentIndex = segmentIndex,
                        format = captureFormat
                    )

                    // Displayable JPEG thumbnail straight from the bitmap so PNG/TIFF segments
                    // (which Coil can't render) still show in the gallery and results.
                    thumbnailPath = imageRepository.createThumbnailFromBitmap(
                        bitmap = croppedBitmap,
                        sessionId = session.id,
                        segmentIndex = segmentIndex
                    )
                    segWidth = croppedBitmap.width
                    segHeight = croppedBitmap.height
                }

                // Use fixed overlap percentage from settings (no auto-calculation)
                val overlap = overlapPercentage.value / 100f

                // Determine if this is a multi-frame session (more than 1 segment expected)
                // User typically captures multiple frames, so default to true
                val isMultiFrame = _capturedSegments.value.size > 0 || segmentIndex > 0

                // Add segment to database
                val segment = sessionRepository.addSegment(
                    sessionId = session.id,
                    imagePath = imagePath,
                    thumbnailPath = thumbnailPath,
                    width = segWidth,
                    height = segHeight,
                    iso = capturedImage.iso,
                    shutterSpeed = capturedImage.shutterSpeed,
                    focusDistance = capturedImage.focusDistance,
                    whiteBalance = capturedImage.whiteBalance.temperature,
                    exposureCompensation = capturedImage.exposureCompensation,
                    overlapPercentage = overlap,
                    isMultiFrameSession = isMultiFrame
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
     *
     * Key insight: The cropRect values (0-1) represent fractions of the VISIBLE preview area,
     * not the full captured image. We need to:
     * 1. Determine which portion of the full image is visible in the preview
     * 2. Map the crop rectangle (which is relative to this visible portion) to absolute image coordinates
     */
    private fun cropBitmap(source: Bitmap, cropRect: CropRect): Bitmap {
        val imageWidth = source.width.toFloat()
        val imageHeight = source.height.toFloat()
        val imageAspect = imageWidth / imageHeight

        val viewAspect = if (previewWidth > 0 && previewHeight > 0) {
            previewWidth.toFloat() / previewHeight.toFloat()
        } else {
            imageAspect // Fallback: assume no letterboxing
        }

        // With FIT_CENTER the WHOLE image is shown, centered, with letterbox bars. The crop
        // rect fractions are relative to the full PreviewView (including bars), so map each
        // axis from view-fraction → image-fraction by removing the bar offset/scale.
        val (leftF, rightF, topF, bottomF) = if (imageAspect > viewAspect) {
            // Bars on top/bottom: image fills width, occupies a centered vertical band.
            val bandFrac = viewAspect / imageAspect          // fraction of view height covered by image
            val barFrac = (1f - bandFrac) / 2f
            CropFractions(
                left = cropRect.left,
                right = cropRect.right,
                top = ((cropRect.top - barFrac) / bandFrac),
                bottom = ((cropRect.bottom - barFrac) / bandFrac)
            )
        } else {
            // Bars on left/right: image fills height, occupies a centered horizontal band.
            val bandFrac = imageAspect / viewAspect          // fraction of view width covered by image
            val barFrac = (1f - bandFrac) / 2f
            CropFractions(
                left = ((cropRect.left - barFrac) / bandFrac),
                right = ((cropRect.right - barFrac) / bandFrac),
                top = cropRect.top,
                bottom = cropRect.bottom
            )
        }

        val left = (leftF.coerceIn(0f, 1f) * imageWidth).toInt().coerceIn(0, source.width - 1)
        val top = (topF.coerceIn(0f, 1f) * imageHeight).toInt().coerceIn(0, source.height - 1)
        val right = (rightF.coerceIn(0f, 1f) * imageWidth).toInt().coerceIn(left + 1, source.width)
        val bottom = (bottomF.coerceIn(0f, 1f) * imageHeight).toInt().coerceIn(top + 1, source.height)

        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private data class CropFractions(
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float
    )

    /**
     * Called when user taps "Finish Session".
     * If multiple segments: loads images and shows manual alignment screen.
     * If single segment: directly completes the session (no alignment needed).
     */
    fun finishSession() {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val segments = _capturedSegments.value

            if (segments.isEmpty()) {
                _uiState.update { it.copy(error = "No segments captured") }
                return@launch
            }

            // Skip alignment screen if only one segment
            if (segments.size == 1) {
                finishWithSingleSegment(session, segments.first())
                return@launch
            }

            _uiState.update { it.copy(
                isProcessing = true,
                message = "Loading images for alignment..."
            )}

            try {
                // Load all segment images for alignment preview
                val bitmaps = segments.mapNotNull { segment ->
                    imageRepository.loadBitmap(segment.imagePath)
                }

                if (bitmaps.isEmpty()) {
                    throw Exception("Failed to load segment images")
                }

                // Store bitmaps for alignment screen
                _alignmentBitmaps.value = bitmaps

                _uiState.update { it.copy(
                    isProcessing = false,
                    message = null
                )}

                // Show manual alignment screen
                _showManualAlignment.value = true

            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isProcessing = false,
                    error = "Failed to load images: ${e.message}"
                )}
            }
        }
    }

    /**
     * Completes the session keeping only the individual full-resolution segments,
     * without stitching them into a panorama. This is the default "Save frames" path —
     * stitching becomes an optional step the user can run later from the results screen.
     */
    fun finishSessionFramesOnly() {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val segments = _capturedSegments.value

            if (segments.isEmpty()) {
                _uiState.update { it.copy(error = "No segments captured") }
                return@launch
            }

            _uiState.update { it.copy(isProcessing = true, message = "Saving frames...") }

            try {
                sessionRepository.completeSessionWithoutStitch(session.id)

                _uiState.update { it.copy(
                    isProcessing = false,
                    sessionActive = false,
                    sessionComplete = true,
                    stitchedImagePath = null,
                    message = "${segments.size} frame${if (segments.size != 1) "s" else ""} saved!"
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isProcessing = false,
                    error = "Failed to save frames: ${e.message}"
                )}
            }
        }
    }

    // ==================== Simple (fast field) Mode ====================

    /** Enters Simple mode: one image per leaf, edit metadata inline, review, export, repeat. */
    fun startSimpleMode(farmerId: String, fieldId: String, treatment: String, leafNumber: Int) {
        _simpleFarmerId.value = farmerId
        _simpleFieldId.value = fieldId
        _simpleTreatment.value = treatment
        _simpleLeafNumber.value = leafNumber.coerceAtLeast(1)
        _pendingCapture.value = null
        _simpleMode.value = true
        _uiState.update { it.copy(message = "Simple mode — set details, then capture") }
    }

    fun setSimpleFarmerId(value: String) { _simpleFarmerId.value = value }
    fun setSimpleFieldId(value: String) { _simpleFieldId.value = value }
    fun setSimpleTreatment(value: String) { _simpleTreatment.value = value }
    fun setSimpleLeafNumber(value: Int) { _simpleLeafNumber.value = value.coerceAtLeast(1) }

    /** Holds a freshly captured image for Retake/Export review. */
    fun onSimpleCaptured(image: CapturedImage) {
        _pendingCapture.value = image
    }

    /** Discards the pending capture and returns to the live camera. */
    fun retakeSimple() {
        _pendingCapture.value?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        _pendingCapture.value = null
    }

    /** Commits the pending capture: saves a single-image session AND exports it to Photos. */
    fun exportSimpleCapture() {
        viewModelScope.launch {
            val image = _pendingCapture.value ?: return@launch
            _uiState.update { it.copy(isProcessing = true, message = "Saving & exporting...") }
            try {
                val farmerId = _simpleFarmerId.value
                val fieldId = _simpleFieldId.value
                val treatment = _simpleTreatment.value
                val leaf = _simpleLeafNumber.value

                // 1. Single-image session (kept in the app gallery)
                val session = sessionRepository.createSession(
                    farmerId = farmerId,
                    fieldId = fieldId,
                    treatment = treatment,
                    leafNumber = leaf,
                    latitude = currentLocation?.latitude,
                    longitude = currentLocation?.longitude,
                    altitude = currentLocation?.altitude,
                    locationAccuracy = currentLocation?.accuracy
                )

                // 2. Save the image as the session's single segment
                val imagePath: String
                val thumbPath: String
                val w: Int
                val h: Int
                if (image.filePath != null) {
                    imagePath = imageRepository.saveSegmentFile(image.filePath, session.id, 0)
                    thumbPath = imagePath
                    w = image.width; h = image.height
                    image.dngFilePath?.let { dng ->
                        imageRepository.saveRawToGallery(dng, simpleFileName(farmerId, fieldId, treatment, leaf, "dng"))
                    }
                } else {
                    val bmp = image.bitmap ?: throw Exception("No image data")
                    val format = _cameraSettings.value.captureFormat
                    imagePath = imageRepository.saveSegmentImage(bmp, session.id, 0, format = format)
                    thumbPath = imageRepository.createThumbnailFromBitmap(bmp, session.id, 0)
                    w = bmp.width; h = bmp.height
                }

                sessionRepository.addSegment(
                    sessionId = session.id,
                    imagePath = imagePath,
                    thumbnailPath = thumbPath,
                    width = w,
                    height = h,
                    iso = image.iso,
                    shutterSpeed = image.shutterSpeed,
                    focusDistance = image.focusDistance,
                    whiteBalance = image.whiteBalance.temperature,
                    exposureCompensation = image.exposureCompensation,
                    isMultiFrameSession = false
                )
                sessionRepository.completeSessionWithoutStitch(session.id)

                // 3. Export the image to Photos
                val settings = exportSettings.value
                imageRepository.exportImage(
                    imagePath,
                    settings,
                    simpleFileName(farmerId, fieldId, treatment, leaf, settings.format.extension)
                )

                // 4. Clear pending, ready for next leaf
                image.bitmap?.let { if (!it.isRecycled) it.recycle() }
                _pendingCapture.value = null

                _uiState.update { it.copy(
                    isProcessing = false,
                    message = "Leaf $leaf saved & exported. Set details for the next."
                )}
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = "Export failed: ${e.message}") }
            }
        }
    }

    /** Exits Simple mode entirely. */
    fun exitSimpleMode() {
        retakeSimple()
        _simpleMode.value = false
        _uiState.update { it.copy(message = null) }
    }

    private fun simpleFileName(farmer: String, field: String, treatment: String, leaf: Int, ext: String): String {
        val parts = mutableListOf("LeafDoc")
        if (farmer.isNotEmpty()) parts.add(farmer.take(20))
        if (field.isNotEmpty()) parts.add(field.take(20))
        if (treatment.isNotEmpty()) parts.add(treatment.take(20))
        parts.add("Leaf$leaf")
        parts.add(System.currentTimeMillis().toString())
        return "${parts.joinToString("_")}.$ext"
    }

    /**
     * Completes session with a single segment (no stitching/alignment needed).
     */
    private suspend fun finishWithSingleSegment(session: LeafSession, segment: CapturedSegmentInfo) {
        _uiState.update { it.copy(
            isProcessing = true,
            isStitching = true,
            message = "Saving image..."
        )}

        try {
            // Clear frame labels for single-segment sessions (user preference)
            clearFrameLabelsForSingleSegment(session.id)

            // Load the single segment
            val bitmap = imageRepository.loadBitmap(segment.imagePath)
                ?: throw Exception("Failed to load segment image")

            // Save as stitched image (even though it's just one segment)
            val stitchedPath = imageRepository.saveStitchedImage(
                bitmap = bitmap,
                sessionId = session.id
            )

            bitmap.recycle()

            // Complete session
            sessionRepository.completeSession(session.id, stitchedPath)

            _uiState.update { it.copy(
                isProcessing = false,
                isStitching = false,
                sessionActive = false,
                sessionComplete = true,
                stitchedImagePath = stitchedPath,
                message = "Leaf image saved successfully!"
            )}
        } catch (e: Exception) {
            _uiState.update { it.copy(
                isProcessing = false,
                isStitching = false,
                error = "Failed to save image: ${e.message}"
            )}
        }
    }

    /**
     * Clears frame labels for single-segment sessions (user preference).
     */
    private suspend fun clearFrameLabelsForSingleSegment(sessionId: String) {
        val segments = sessionRepository.getSegmentsBySessionSync(sessionId)
        if (segments.size == 1) {
            // Update the single segment to have null frameLabel
            sessionRepository.updateSegment(segments[0].copy(frameLabel = null))
        }
    }

    /**
     * Gets auto-detected Y offsets using midrib alignment.
     * Called from alignment screen when user taps "Auto Align".
     */
    suspend fun getAutoAlignOffsets(): List<Int> {
        val bitmaps = _alignmentBitmaps.value
        if (bitmaps.isEmpty()) return emptyList()

        val searchTolerance = midribSearchTolerance.value / 100f
        return midribAligner.detectOffsets(bitmaps, searchTolerance)
    }

    /**
     * Generates a preview bitmap with the given manual offsets.
     * Called from alignment screen for live preview updates.
     */
    suspend fun generatePreview(offsets: List<Int>): Bitmap? {
        val bitmaps = _alignmentBitmaps.value
        if (bitmaps.isEmpty()) return null

        val overlapPercent = overlapPercentage.value / 100f
        return simpleStitcher.createPreview(
            images = bitmaps,
            offsets = offsets,
            overlapPercent = overlapPercent,
            scale = 0.3f
        )
    }

    /**
     * Called when user confirms alignment with their manual offsets.
     * Performs final stitching and completes the session.
     */
    fun confirmAlignment(offsets: List<Int>) {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val bitmaps = _alignmentBitmaps.value

            if (bitmaps.isEmpty()) {
                _uiState.update { it.copy(error = "No images to stitch") }
                return@launch
            }

            // Hide alignment screen
            _showManualAlignment.value = false

            _uiState.update { it.copy(
                isProcessing = true,
                isStitching = true,
                message = "Stitching ${bitmaps.size} segments..."
            )}

            try {
                // Stitch images with manual offsets
                val overlapPercent = overlapPercentage.value / 100f
                val stitchResult = simpleStitcher.stitchImages(
                    images = bitmaps,
                    overlapPercent = overlapPercent,
                    alignMidrib = false, // Manual offsets override auto-alignment
                    manualOffsets = offsets
                )

                // Clear alignment bitmaps (recycle them)
                clearAlignmentBitmaps()

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
                clearAlignmentBitmaps()
                _uiState.update { it.copy(
                    isProcessing = false,
                    isStitching = false,
                    error = "Failed to complete session: ${e.message}"
                )}
            }
        }
    }

    /**
     * Cancels the alignment process and returns to capture mode.
     */
    fun cancelAlignment() {
        _showManualAlignment.value = false
        clearAlignmentBitmaps()
    }

    /**
     * Clears and recycles alignment bitmaps to free memory.
     */
    private fun clearAlignmentBitmaps() {
        _alignmentBitmaps.value.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        _alignmentBitmaps.value = emptyList()
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

    fun reportCaptureError(message: String) {
        _uiState.update { it.copy(isProcessing = false, error = "Capture failed: $message") }
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

    fun toggleCropRectEnabled() {
        val newEnabled = !_cropRectEnabled.value
        _cropRectEnabled.value = newEnabled
        viewModelScope.launch {
            preferencesManager.updateCropRectEnabled(newEnabled)
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

/** Capture workflow chosen at session start. */
enum class SessionType {
    /** Fast field flow: one image per leaf, edit metadata inline, review, export, repeat. */
    SIMPLE,
    /** Full flow: capture multiple frames, optional stitch + AI diagnosis. */
    DETAILED
}
