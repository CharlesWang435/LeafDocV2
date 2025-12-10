package com.leafdoc.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.leafdoc.app.data.model.CameraSettings
import com.leafdoc.app.data.model.FlashMode
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

    private var frameAnalyzer: FrameAnalyzer? = null

    suspend fun initialize(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        settings: CameraSettings = CameraSettings()
    ) {
        _cameraState.value = CameraState.Initializing

        try {
            cameraProvider = getCameraProvider()
            _currentSettings.value = settings

            // Get camera capabilities
            val capabilities = queryCameraCapabilities()
            _cameraCapabilities.value = capabilities

            // Build use cases
            buildUseCases(settings)

            // Bind to lifecycle
            bindCamera(lifecycleOwner, previewView)

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

    private fun queryCameraCapabilities(): CameraCapabilities {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
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
            apertures = apertures.toList()
        )
    }

    private fun buildUseCases(settings: CameraSettings) {
        // Preview
        preview = Preview.Builder()
            .build()

        // Image Capture
        val targetResolution = when (settings.resolution) {
            ResolutionMode.FULL -> null  // Max available
            ResolutionMode.HIGH -> Size(4000, 3000)
            ResolutionMode.MEDIUM -> Size(3264, 2448)
            ResolutionMode.LOW -> Size(2048, 1536)
        }

        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100)

        targetResolution?.let {
            imageCaptureBuilder.setTargetResolution(it)
        }

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

    private fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraProvider?.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        camera = cameraProvider?.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            imageCapture,
            imageAnalysis
        )

        preview?.setSurfaceProvider(previewView.surfaceProvider)
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

        // Focus distance
        if (settings.focusDistance != CameraSettings.FOCUS_AUTO) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                settings.focusDistance
            )
        } else {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
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
            .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        camera.cameraControl.startFocusAndMetering(action)
    }

    suspend fun captureImage(): CapturedImage = suspendCancellableCoroutine { continuation ->
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
                            exposureCompensation = settings.exposureCompensation
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
    val apertures: List<Float>
)

data class CapturedImage(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val iso: Int?,
    val shutterSpeed: Long?,
    val focusDistance: Float?,
    val whiteBalance: WhiteBalanceMode,
    val exposureCompensation: Float
)

data class ExposureInfo(
    val iso: Int,
    val shutterSpeed: Long,
    val aperture: Float
)
