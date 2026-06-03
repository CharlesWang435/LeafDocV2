package com.leafdoc.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.leafdoc.app.data.model.CameraSettings
import com.leafdoc.app.data.model.CaptureFormat
import com.leafdoc.app.data.model.FlashMode
import com.leafdoc.app.data.model.FocusMode
import com.leafdoc.app.data.model.ResolutionMode
import com.leafdoc.app.data.model.WhiteBalanceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalCamera2Interop::class)
class ProCameraController(
    private val context: Context
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _cameraCapabilities = MutableStateFlow<CameraCapabilities?>(null)
    val cameraCapabilities: StateFlow<CameraCapabilities?> = _cameraCapabilities.asStateFlow()

    private val _currentSettings = MutableStateFlow(CameraSettings())
    val currentSettings: StateFlow<CameraSettings> = _currentSettings.asStateFlow()

    private val _exposureInfo = MutableStateFlow<ExposureInfo?>(null)
    val exposureInfo: StateFlow<ExposureInfo?> = _exposureInfo.asStateFlow()

    private val _histogramData = MutableStateFlow<IntArray?>(null)
    val histogramData: StateFlow<IntArray?> = _histogramData.asStateFlow()

    private val _currentLens = MutableStateFlow<LensInfo?>(null)
    val currentLens: StateFlow<LensInfo?> = _currentLens.asStateFlow()

    // User-selected still-capture resolution for the current lens; null = auto (largest available).
    private val _selectedResolution = MutableStateFlow<Size?>(null)
    val selectedResolution: StateFlow<Size?> = _selectedResolution.asStateFlow()

    // When true, captures shoot uncompressed RAW/DNG (+ a companion JPEG) on RAW-capable lenses.
    private val _rawEnabled = MutableStateFlow(false)
    val rawEnabled: StateFlow<Boolean> = _rawEnabled.asStateFlow()

    fun setRawEnabled(enabled: Boolean) { _rawEnabled.value = enabled }

    // AF lock: when on, tap-to-focus holds focus (no auto-cancel) so it stays put between shots.
    private val _afLocked = MutableStateFlow(false)
    val afLocked: StateFlow<Boolean> = _afLocked.asStateFlow()

    fun setAfLocked(locked: Boolean) {
        _afLocked.value = locked
        // Releasing the lock resumes the active AF mode.
        if (!locked) camera?.cameraControl?.cancelFocusAndMetering()
    }

    fun updateFocusMode(mode: FocusMode) {
        applySettings(_currentSettings.value.copy(focusMode = mode))
    }

    // Optical zoom ratio = lens selection on a logical multi-camera (0.6x ultra-wide → 10x tele).
    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _zoomRange = MutableStateFlow(1f to 1f)
    val zoomRange: StateFlow<Pair<Float, Float>> = _zoomRange.asStateFlow()

    /** Sets the optical/digital zoom ratio (clamped to the camera's supported range). */
    fun setZoom(ratio: Float) {
        val (min, max) = _zoomRange.value
        val clamped = ratio.coerceIn(min, max)
        camera?.cameraControl?.setZoomRatio(clamped)
        _zoomRatio.value = clamped
    }

    private var frameAnalyzer: FrameAnalyzer? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    suspend fun initialize(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        settings: CameraSettings = CameraSettings()
    ) {
        _cameraState.value = CameraState.Initializing

        try {
            this.lifecycleOwner = lifecycleOwner
            this.previewView = previewView

            cameraProvider = getCameraProvider()
            _currentSettings.value = settings

            // Get camera capabilities (includes all available lenses)
            val capabilities = queryCameraCapabilities()
            _cameraCapabilities.value = capabilities

            // Set default lens (main/wide lens)
            val defaultLens = capabilities.availableLenses.firstOrNull {
                it.type == LensType.WIDE || it.type == LensType.NORMAL
            } ?: capabilities.availableLenses.firstOrNull()
            _currentLens.value = defaultLens

            // Build use cases
            buildUseCases(settings)

            // Bind to lifecycle
            bindCamera(lifecycleOwner, previewView, defaultLens?.id)

            // Apply initial settings
            applySettings(settings)

            _cameraState.value = CameraState.Ready
        } catch (e: Exception) {
            _cameraState.value = CameraState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                continuation.resume(future.get())
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun createLensInfo(cameraId: String, characteristics: CameraCharacteristics): LensInfo? {
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
        val focalLength = focalLengths.firstOrNull() ?: return null

        // Get sensor size for better lens classification
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorWidth = sensorSize?.width ?: 6.4f // Default to typical phone sensor

        // Calculate 35mm equivalent focal length for better classification
        // Crop factor = 36mm / sensor width
        val cropFactor = 36f / sensorWidth
        val focalLength35mm = focalLength * cropFactor

        // Determine lens type based on 35mm equivalent focal length
        val lensType = when {
            focalLength35mm < 18f -> LensType.ULTRA_WIDE  // < 18mm (typically 0.5x-0.6x)
            focalLength35mm < 28f -> LensType.WIDE       // 18-28mm (typically 0.8x-1x)
            focalLength35mm < 50f -> LensType.NORMAL     // 28-50mm (typically 1x-1.5x)
            focalLength35mm < 85f -> LensType.TELEPHOTO  // 50-85mm (typically 2x-3x)
            else -> LensType.TELEPHOTO                   // > 85mm (typically 5x-10x)
        }

        // Check if it's a macro lens (minimum focus distance < 10cm)
        val minFocusDist = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val isMacro = minFocusDist > 0 && (1f / minFocusDist) < 0.1f

        val finalLensType = if (isMacro) LensType.MACRO else lensType

        // Get zoom ratio if available (API 30+) to determine display multiplier
        val zoomRatio = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1.0f
        } else {
            // Estimate zoom ratio from focal length
            when {
                focalLength35mm < 18f -> 0.6f
                focalLength35mm < 28f -> 1.0f
                focalLength35mm < 50f -> 1.0f
                focalLength35mm < 85f -> when {
                    focalLength35mm > 70f -> 3.0f
                    else -> 2.0f
                }
                focalLength35mm < 135f -> 5.0f
                else -> 10.0f
            }
        }

        // Create display name based on zoom ratio
        val displayName = when (finalLensType) {
            LensType.ULTRA_WIDE -> "Ultra Wide (${zoomRatio}x)"
            LensType.WIDE -> "Wide (${zoomRatio}x)"
            LensType.NORMAL -> "Main (${zoomRatio}x)"
            LensType.TELEPHOTO -> {
                val roundedZoom = when {
                    zoomRatio >= 9f -> "10x"
                    zoomRatio >= 4.5f -> "5x"
                    zoomRatio >= 2.5f -> "3x"
                    else -> "2x"
                }
                "Telephoto ($roundedZoom)"
            }
            LensType.MACRO -> "Macro"
            LensType.UNKNOWN -> "Camera $cameraId"
        }

        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK

        // Available standard (binned) JPEG sizes for THIS lens.
        val standardSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.toList()
            ?: emptyList()

        // Full-sensor maximum-resolution JPEG sizes (e.g. 50MP/200MP). Gated on the max-res
        // stream map EXISTING — NOT on the ULTRA_HIGH_RESOLUTION_SENSOR capability flag, which
        // Samsung leaves unset even though the framework max-res path works. API 31+ only.
        val maxResSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.filter { mr -> standardSizes.none { it.width == mr.width && it.height == mr.height } }
                ?: emptyList()
        } else emptyList()

        // RAW capability for this lens (uncompressed sensor capture via DngCreator).
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val rawSize = if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width.toLong() * it.height }
        } else null

        // Picker shows max-res sizes + standard sizes, largest first.
        val allSizes = (maxResSizes + standardSizes)
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.width.toLong() * it.height }

        return LensInfo(
            id = cameraId,
            lensFacing = lensFacing,
            type = finalLensType,
            focalLength = focalLength,
            physicalSize = sensorSize,
            zoomRatio = zoomRatio,
            displayName = displayName,
            stillSizes = allSizes,
            maxResSizes = maxResSizes,
            rawSize = rawSize
        )
    }

    private fun queryCameraCapabilities(): CameraCapabilities {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Find all back-facing cameras (including physical cameras)
        val availableLenses = mutableListOf<LensInfo>()
        val addedCameraIds = mutableSetOf<String>() // Track added cameras to avoid duplicates

        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

            // Only include back-facing cameras
            // IMPORTANT: Only add logical camera IDs, not physical camera IDs
            // Physical cameras can't be bound directly in CameraX
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                if (!addedCameraIds.contains(cameraId)) {
                    val lensInfo = createLensInfo(cameraId, characteristics)
                    if (lensInfo != null) {
                        availableLenses.add(lensInfo)
                        addedCameraIds.add(cameraId)
                    }
                }
            }
        }

        // Sort lenses by focal length (ultra-wide -> wide -> telephoto)
        availableLenses.sortBy { it.focalLength }

        // Use the first back-facing camera as primary for capability detection
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.first()

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        // ISO range
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: Range(100, 3200)

        // Exposure time range (shutter speed)
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(1000000L, 1000000000L)

        // Focus distance range
        val minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val hyperfocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f

        // Available resolutions
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()

        // Exposure compensation range
        val exposureCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: Range(-12, 12)
        val exposureCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()
            ?: 0.5f

        // Flash availability
        val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        // Focal length
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf(4.25f)

        // Apertures
        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?: floatArrayOf(1.8f)

        return CameraCapabilities(
            isoRange = isoRange,
            exposureTimeRange = exposureRange,
            minFocusDistance = minFocusDistance,
            hyperfocalDistance = hyperfocalDistance,
            availableResolutions = outputSizes,
            exposureCompensationRange = exposureCompensationRange,
            exposureCompensationStep = exposureCompensationStep,
            hasFlash = hasFlash,
            focalLengths = focalLengths.toList(),
            apertures = apertures.toList(),
            availableLenses = availableLenses
        )
    }

    private fun buildUseCases(settings: CameraSettings) {
        // Preview
        preview = Preview.Builder()
            .build()

        // Available still sizes for the CURRENT lens (falls back to the primary camera's list).
        val lensSizes = _currentLens.value?.stillSizes?.takeIf { it.isNotEmpty() }
            ?: _cameraCapabilities.value?.availableResolutions
            ?: emptyList()
        val maxForLens = lensSizes.maxByOrNull { it.width.toLong() * it.height }

        // Resolution priority: explicit user pick > ResolutionMode preset > largest available.
        val target: Size? = _selectedResolution.value ?: when (settings.resolution) {
            ResolutionMode.FULL -> maxForLens
            ResolutionMode.HIGH -> maxForLens?.let { Size(it.width * 3 / 4, it.height * 3 / 4) }
            ResolutionMode.MEDIUM -> maxForLens?.let { Size(it.width / 2, it.height / 2) }
            ResolutionMode.LOW -> Size(2048, 1536)
        }

        // CameraX always captures a JPEG from the sensor; PNG/TIFF are produced by re-encoding
        // the decoded bitmap at save time. True RAW/DNG is a separate Camera2 path (Phase 5).
        val resolutionSelector = ResolutionSelector.Builder().apply {
            if (target != null) {
                setResolutionStrategy(
                    ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
            } else {
                setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            }
        }.build()

        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100)  // Maximum JPEG quality for detailed scientific imaging
            .setResolutionSelector(resolutionSelector)

        imageCapture = imageCaptureBuilder.build()

        // Image Analysis for histogram
        frameAnalyzer = FrameAnalyzer { histogram ->
            _histogramData.value = histogram
        }

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor, frameAnalyzer!!)
            }
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView, cameraId: String? = null) {
        cameraProvider?.unbindAll()

        val cameraSelector = if (cameraId != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // Use specific camera by ID (for multi-lens support)
            try {
                CameraSelector.Builder()
                    .addCameraFilter { cameras ->
                        val filtered = cameras.filter { cameraInfo ->
                            val info = Camera2CameraInfo.from(cameraInfo)
                            info.cameraId == cameraId
                        }
                        // If filter returns empty, fall back to all cameras
                        if (filtered.isEmpty()) cameras else filtered
                    }
                    .build()
            } catch (e: Exception) {
                timber.log.Timber.w(e, "Failed to create camera selector for ID $cameraId, using default")
                // Fallback to default back camera
                CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()
            }
        } else {
            // Default to back camera
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
        }

        camera = cameraProvider?.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            imageCapture,
            imageAnalysis
        )

        preview?.setSurfaceProvider(previewView.surfaceProvider)

        // Read the optical zoom range of this (logical) camera. On modern phones the
        // ultra-wide / main / telephoto lenses are fused behind one logical camera and are
        // selected by zoom ratio (e.g. 0.6x–10x), not by switching camera IDs.
        camera?.let { cam ->
            // Authoritative range from Camera2 (CameraX zoomState.value can be null right after bind).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Camera2CameraInfo.from(cam.cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        ?.let { _zoomRange.value = it.lower to it.upper }
                } catch (_: Exception) {}
            }
            cam.cameraInfo.zoomState.value?.let { zs ->
                if (_zoomRange.value.second <= _zoomRange.value.first) {
                    _zoomRange.value = zs.minZoomRatio to zs.maxZoomRatio
                }
                _zoomRatio.value = zs.zoomRatio
            }
        }
    }

    /**
     * Switch to a different lens/camera
     */
    fun switchLens(lensInfo: LensInfo) {
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return

        _currentLens.value = lensInfo
        // Sizes differ per lens, so reset to that lens's maximum and rebuild the capture use case.
        _selectedResolution.value = null
        buildUseCases(_currentSettings.value)
        bindCamera(owner, view, lensInfo.id)

        // Reapply current settings to new camera
        applySettings(_currentSettings.value)
    }

    /**
     * Selects an explicit still-capture resolution for the current lens (null = auto/largest).
     * Rebuilds the capture use case and re-binds so the change takes effect immediately.
     */
    fun selectResolution(size: Size?) {
        _selectedResolution.value = size
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return
        buildUseCases(_currentSettings.value)
        bindCamera(owner, view, _currentLens.value?.id)
        applySettings(_currentSettings.value)
    }

    fun applySettings(settings: CameraSettings) {
        _currentSettings.value = settings
        val camera = this.camera ?: return

        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val optionsBuilder = CaptureRequestOptions.Builder()

        // ISO
        if (settings.iso != CameraSettings.ISO_AUTO) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                settings.iso
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
        } else {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
        }

        // Shutter speed
        if (settings.shutterSpeed != CameraSettings.SHUTTER_AUTO && settings.iso != CameraSettings.ISO_AUTO) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                settings.shutterSpeed
            )
        }

        // Focus mode
        when (settings.focusMode) {
            FocusMode.CONTINUOUS -> optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            FocusMode.SINGLE -> optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO
            )
            FocusMode.MACRO -> optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO
            )
            FocusMode.INFINITY -> {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF
                )
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE, 0f // 0 diopters = focus at infinity
                )
            }
            FocusMode.MANUAL -> {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF
                )
                val distance = if (settings.focusDistance >= 0f) settings.focusDistance else 0f
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE, distance
                )
            }
        }

        // White balance
        when (settings.whiteBalance) {
            WhiteBalanceMode.AUTO -> {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                )
            }
            WhiteBalanceMode.DAYLIGHT -> {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                )
            }
            WhiteBalanceMode.CLOUDY -> {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                )
            }
            WhiteBalanceMode.TUNGSTEN -> {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                )
            }
            WhiteBalanceMode.FLUORESCENT -> {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                )
            }
            WhiteBalanceMode.SHADE -> {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_SHADE
                )
            }
            else -> {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                )
            }
        }

        camera2Control.captureRequestOptions = optionsBuilder.build()

        // Exposure compensation (works with AE on)
        if (settings.iso == CameraSettings.ISO_AUTO) {
            val capabilities = _cameraCapabilities.value
            if (capabilities != null) {
                val index = (settings.exposureCompensation / capabilities.exposureCompensationStep).toInt()
                camera.cameraControl.setExposureCompensationIndex(index)
            }
        }

        // Flash mode
        imageCapture?.flashMode = when (settings.flashMode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.TORCH -> {
                camera.cameraControl.enableTorch(true)
                ImageCapture.FLASH_MODE_OFF
            }
        }

        if (settings.flashMode != FlashMode.TORCH) {
            camera.cameraControl.enableTorch(false)
        }
    }

    fun updateIso(iso: Int) {
        applySettings(_currentSettings.value.copy(iso = iso))
    }

    fun updateShutterSpeed(shutterSpeed: Long) {
        applySettings(_currentSettings.value.copy(shutterSpeed = shutterSpeed))
    }

    fun updateFocusDistance(distance: Float) {
        applySettings(_currentSettings.value.copy(focusDistance = distance))
    }

    fun updateWhiteBalance(mode: WhiteBalanceMode) {
        applySettings(_currentSettings.value.copy(whiteBalance = mode))
    }

    fun updateExposureCompensation(ev: Float) {
        applySettings(_currentSettings.value.copy(exposureCompensation = ev))
    }

    fun updateFlashMode(mode: FlashMode) {
        applySettings(_currentSettings.value.copy(flashMode = mode))
    }

    fun focusOnPoint(x: Float, y: Float, previewView: PreviewView) {
        val camera = this.camera ?: return

        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .apply {
                // When AF is locked, hold focus indefinitely (no auto-cancel).
                if (_afLocked.value) disableAutoCancel()
                else setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            .build()

        camera.cameraControl.startFocusAndMetering(action)
    }

    /**
     * Captures a still image. Routes to the Camera2 maximum-resolution engine when the user
     * has selected a full-sensor size (50/200MP); otherwise uses the standard CameraX path.
     */
    suspend fun captureImage(): CapturedImage {
        val lens = _currentLens.value
        val size = _selectedResolution.value
        val isMaxRes = size != null && lens != null &&
            lens.maxResSizes.any { it.width == size.width && it.height == size.height }

        return when {
            _rawEnabled.value && lens?.rawSize != null -> captureRaw(lens, lens.rawSize)
            isMaxRes && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> captureHighRes(lens!!, size!!)
            else -> captureWithCameraX()
        }
    }

    /**
     * Uncompressed RAW/DNG capture via Camera2 (plus a companion JPEG for in-app display).
     * Same unbind → capture → re-bind handoff as the high-res path.
     */
    private suspend fun captureRaw(lens: LensInfo, rawSize: Size): CapturedImage {
        _cameraState.value = CameraState.Capturing
        val settings = _currentSettings.value
        try {
            cameraProvider?.unbindAll()

            // Companion JPEG at the lens's largest standard size.
            val jpegSize = lens.stillSizes.firstOrNull { it !in lens.maxResSizes } ?: rawSize
            val engine = HighResCaptureEngine(context)
            val result = try {
                engine.captureRaw(lens.id, rawSize, jpegSize, settings)
            } catch (e: Exception) {
                timber.log.Timber.w(e, "RAW capture failed, retrying once")
                kotlinx.coroutines.delay(500)
                engine.captureRaw(lens.id, rawSize, jpegSize, settings)
            }

            return CapturedImage(
                bitmap = null,
                filePath = result.jpegPath,     // companion JPEG = in-app working image
                dngFilePath = result.dngPath,   // uncompressed master
                width = jpegSize.width,
                height = jpegSize.height,
                timestamp = System.currentTimeMillis(),
                iso = if (settings.iso != CameraSettings.ISO_AUTO) settings.iso else null,
                shutterSpeed = if (settings.shutterSpeed != CameraSettings.SHUTTER_AUTO) settings.shutterSpeed else null,
                focusDistance = if (settings.focusDistance != CameraSettings.FOCUS_AUTO) settings.focusDistance else null,
                whiteBalance = settings.whiteBalance,
                exposureCompensation = settings.exposureCompensation,
                captureFormat = settings.captureFormat
            )
        } finally {
            val owner = lifecycleOwner
            val view = previewView
            if (owner != null && view != null) {
                buildUseCases(settings)
                bindCamera(owner, view, lens.id)
                applySettings(settings)
            }
            _cameraState.value = CameraState.Ready
        }
    }

    /**
     * Full-sensor capture via Camera2: unbind CameraX (release the device), shoot with
     * SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION, write JPEG straight to disk, then re-bind CameraX.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun captureHighRes(lens: LensInfo, size: Size): CapturedImage {
        _cameraState.value = CameraState.Capturing
        val settings = _currentSettings.value
        try {
            cameraProvider?.unbindAll() // release the device for Camera2

            val engine = HighResCaptureEngine(context)
            // Retry once: CameraX may not have fully released the device yet (ERROR_CAMERA_IN_USE).
            val result = try {
                engine.capture(lens.id, size, settings)
            } catch (e: Exception) {
                timber.log.Timber.w(e, "High-res capture failed, retrying once")
                kotlinx.coroutines.delay(500)
                engine.capture(lens.id, size, settings)
            }

            return CapturedImage(
                bitmap = null,
                filePath = result.filePath,
                width = result.width,
                height = result.height,
                timestamp = System.currentTimeMillis(),
                iso = if (settings.iso != CameraSettings.ISO_AUTO) settings.iso else null,
                shutterSpeed = if (settings.shutterSpeed != CameraSettings.SHUTTER_AUTO) settings.shutterSpeed else null,
                focusDistance = if (settings.focusDistance != CameraSettings.FOCUS_AUTO) settings.focusDistance else null,
                whiteBalance = settings.whiteBalance,
                exposureCompensation = settings.exposureCompensation,
                captureFormat = settings.captureFormat
            )
        } finally {
            // Re-bind CameraX preview for the next shot.
            val owner = lifecycleOwner
            val view = previewView
            if (owner != null && view != null) {
                buildUseCases(settings)
                bindCamera(owner, view, lens.id)
                applySettings(settings)
            }
            _cameraState.value = CameraState.Ready
        }
    }

    private suspend fun captureWithCameraX(): CapturedImage = suspendCancellableCoroutine { continuation ->
        val imageCapture = this.imageCapture ?: run {
            continuation.resumeWithException(Exception("ImageCapture not initialized"))
            return@suspendCancellableCoroutine
        }

        _cameraState.value = CameraState.Capturing

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = imageProxyToBitmap(image)
                        val settings = _currentSettings.value

                        val capturedImage = CapturedImage(
                            bitmap = bitmap,
                            width = bitmap.width,
                            height = bitmap.height,
                            timestamp = System.currentTimeMillis(),
                            iso = if (settings.iso != CameraSettings.ISO_AUTO) settings.iso else null,
                            shutterSpeed = if (settings.shutterSpeed != CameraSettings.SHUTTER_AUTO) settings.shutterSpeed else null,
                            focusDistance = if (settings.focusDistance != CameraSettings.FOCUS_AUTO) settings.focusDistance else null,
                            whiteBalance = settings.whiteBalance,
                            exposureCompensation = settings.exposureCompensation,
                            captureFormat = settings.captureFormat
                        )

                        _cameraState.value = CameraState.Ready
                        continuation.resume(capturedImage)
                    } catch (e: Exception) {
                        _cameraState.value = CameraState.Error(e.message ?: "Capture failed")
                        continuation.resumeWithException(e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    _cameraState.value = CameraState.Error(exception.message ?: "Capture failed")
                    continuation.resumeWithException(exception)
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Handle rotation
        val rotationDegrees = image.imageInfo.rotationDegrees
        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it != bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }
    }

    fun release() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        frameAnalyzer = null
    }
}

sealed class CameraState {
    data object Idle : CameraState()
    data object Initializing : CameraState()
    data object Ready : CameraState()
    data object Capturing : CameraState()
    data class Error(val message: String) : CameraState()
}

data class CameraCapabilities(
    val isoRange: Range<Int>,
    val exposureTimeRange: Range<Long>,
    val minFocusDistance: Float,
    val hyperfocalDistance: Float,
    val availableResolutions: List<Size>,
    val exposureCompensationRange: Range<Int>,
    val exposureCompensationStep: Float,
    val hasFlash: Boolean,
    val focalLengths: List<Float>,
    val apertures: List<Float>,
    val availableLenses: List<LensInfo> = emptyList()
)

data class LensInfo(
    val id: String,
    val lensFacing: Int,
    val type: LensType,
    val focalLength: Float,
    val physicalSize: android.util.SizeF?,
    val zoomRatio: Float,
    val displayName: String,
    val stillSizes: List<Size> = emptyList(),
    /** Sizes that require the Camera2 maximum-resolution path (full-sensor 50/200MP). */
    val maxResSizes: List<Size> = emptyList(),
    /** Largest RAW_SENSOR size if this lens supports RAW/DNG capture, else null. */
    val rawSize: Size? = null
)

enum class LensType {
    ULTRA_WIDE,  // < 18mm equivalent
    WIDE,        // 18-35mm equivalent
    NORMAL,      // 35-70mm equivalent
    TELEPHOTO,   // > 70mm equivalent
    MACRO,       // Close-up lens
    UNKNOWN
}

data class CapturedImage(
    // Standard (CameraX) captures carry a decoded bitmap; high-res captures carry only a
    // file path (the JPEG is written straight to disk to avoid a huge bitmap allocation).
    val bitmap: Bitmap?,
    val filePath: String? = null,
    /** Path to a companion RAW/DNG master, when captured in RAW mode. */
    val dngFilePath: String? = null,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val iso: Int?,
    val shutterSpeed: Long?,
    val focusDistance: Float?,
    val whiteBalance: WhiteBalanceMode,
    val exposureCompensation: Float,
    val captureFormat: CaptureFormat
)

data class ExposureInfo(
    val iso: Int,
    val shutterSpeed: Long,
    val aperture: Float
)
