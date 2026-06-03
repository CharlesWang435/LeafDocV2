package com.leafdoc.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.annotation.RequiresApi
import com.leafdoc.app.data.model.CameraSettings
import com.leafdoc.app.data.model.WhiteBalanceMode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2 still-capture engine for full-sensor maximum-resolution JPEG capture
 * (e.g. 50MP / 200MP on phones whose default CameraX pipeline only yields the
 * binned ~12MP). Uses `SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION` with the maximum
 * resolution stream-configuration map.
 *
 * This temporarily takes over the camera device, so the caller must unbind CameraX
 * (release the device) before calling [capture] and re-bind it afterward. The JPEG
 * bytes are written straight to a file — never decoded into a Bitmap — so a 200MP
 * shot doesn't allocate a ~1GB bitmap.
 *
 * The max-res JPEG path requires API 31+ (SENSOR_PIXEL_MODE). The RAW/DNG path works on
 * API 26+. Detection/gating lives in ProCameraController.
 */
class HighResCaptureEngine(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    data class HighResResult(val filePath: String, val width: Int, val height: Int)

    /** RAW capture also produces a companion JPEG for in-app display/AI (DNG isn't decodable). */
    data class RawResult(val dngPath: String, val jpegPath: String, val width: Int, val height: Int)

    /**
     * Captures a single maximum-resolution JPEG from [cameraId] at [size].
     * Mirrors the manual controls in [settings] (ISO/exposure/focus/AWB/EV) when set,
     * otherwise uses auto 3A. Runs entirely on a private background thread.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission") // CAMERA permission is enforced by the capture screen before we get here.
    suspend fun capture(
        cameraId: String,
        size: Size,
        settings: CameraSettings
    ): HighResResult {
        val thread = HandlerThread("HighResCapture").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { command -> handler.post(command) }

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null

        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)

            device = openDevice(cameraId, handler)
            session = createMaxResSession(device, reader.surface, executor)

            val outFile = File(context.cacheDir, "highres_${System.nanoTime()}.jpg")
            captureToFile(device, session, reader, sensorOrientation, settings, outFile, handler)

            return HighResResult(outFile.absolutePath, size.width, size.height)
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            thread.quitSafely()
        }
    }

    /**
     * Captures an uncompressed RAW/DNG at [rawSize] plus a companion JPEG at [jpegSize]
     * (the DNG can't be decoded in-app, so the JPEG is used for display/AI). Writes both
     * straight to disk. Uses the standard (non-max-res) session.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureRaw(
        cameraId: String,
        rawSize: Size,
        jpegSize: Size,
        settings: CameraSettings
    ): RawResult {
        val thread = HandlerThread("RawCapture").apply { start() }
        val handler = Handler(thread.looper)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var rawReader: ImageReader? = null
        var jpegReader: ImageReader? = null

        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 1)
            jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 1)

            device = openDevice(cameraId, handler)
            session = createStandardSession(device, listOf(rawReader.surface, jpegReader.surface), handler)

            val dngFile = File(context.cacheDir, "raw_${System.nanoTime()}.dng")
            val jpegFile = File(context.cacheDir, "raw_${System.nanoTime()}.jpg")
            captureRawAndJpeg(
                device, session, characteristics, rawReader, jpegReader,
                sensorOrientation, settings, dngFile, jpegFile, handler
            )

            return RawResult(dngFile.absolutePath, jpegFile.absolutePath, rawSize.width, rawSize.height)
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { rawReader?.close() } catch (_: Exception) {}
            try { jpegReader?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            thread.quitSafely()
        }
    }

    @Suppress("DEPRECATION") // legacy createCaptureSession keeps RAW path working on API 26-27
    private suspend fun createStandardSession(
        device: CameraDevice,
        surfaces: List<android.view.Surface>,
        handler: Handler
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { cont.resume(s) }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(Exception("RAW session configuration failed"))
            }
        }, handler)
    }

    private suspend fun captureRawAndJpeg(
        device: CameraDevice,
        session: CameraCaptureSession,
        characteristics: CameraCharacteristics,
        rawReader: ImageReader,
        jpegReader: ImageReader,
        sensorOrientation: Int,
        settings: CameraSettings,
        dngFile: File,
        jpegFile: File,
        handler: Handler
    ): Unit = suspendCancellableCoroutine { cont ->
        // All callbacks below run on the single capture HandlerThread, so this state is
        // touched serially — no locking needed. `resolved` makes resume happen exactly once
        // (a continuation resumed twice crashes), and guarantees the full-sensor RAW Image is
        // closed on every terminal path (the ImageReader has only 1 buffer; a leaked Image
        // jams the next RAW capture).
        var rawImage: Image? = null
        var totalResult: TotalCaptureResult? = null
        var jpegDone = false
        var dngDone = false
        var resolved = false

        fun closeRawImage() {
            try { rawImage?.close() } catch (_: Exception) {}
            rawImage = null
        }

        fun resolveError(e: Throwable) {
            if (resolved) return
            resolved = true
            closeRawImage()
            if (cont.isActive) cont.resumeWithException(e)
        }

        fun finishIfReady() {
            if (jpegDone && dngDone && !resolved) {
                resolved = true
                if (cont.isActive) cont.resume(Unit)
            }
        }

        // DngCreator needs both the RAW image AND the capture result.
        fun tryWriteDng() {
            val img = rawImage
            val res = totalResult
            if (img != null && res != null && !dngDone && !resolved) {
                try {
                    DngCreator(characteristics, res).use { dng ->
                        dng.setOrientation(exifFromDegrees(sensorOrientation))
                        FileOutputStream(dngFile).use { dng.writeImage(it, img) }
                    }
                    dngDone = true
                    closeRawImage()
                    finishIfReady()
                } catch (e: Exception) {
                    resolveError(e)
                }
            }
        }

        rawReader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireNextImage() } catch (e: Exception) { null }
            if (img == null) { resolveError(Exception("No RAW image produced")); return@setOnImageAvailableListener }
            if (resolved) { try { img.close() } catch (_: Exception) {}; return@setOnImageAvailableListener }
            rawImage = img
            tryWriteDng()
        }, handler)

        jpegReader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireNextImage() } catch (e: Exception) { null }
            if (image == null) { resolveError(Exception("No companion JPEG produced")); return@setOnImageAvailableListener }
            if (resolved) { try { image.close() } catch (_: Exception) {}; return@setOnImageAvailableListener }
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                FileOutputStream(jpegFile).use { it.write(bytes) }
                jpegDone = true
                finishIfReady()
            } catch (e: Exception) {
                resolveError(e)
            } finally {
                image.close()
            }
        }, handler)

        // If the calling coroutine is cancelled mid-capture, drop the in-flight sensor buffer.
        cont.invokeOnCancellation { closeRawImage() }

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawReader.surface)
            addTarget(jpegReader.surface)
            set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            applyManualControls(this, settings)
        }.build()

        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                totalResult = result
                tryWriteDng()
            }
            override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, failure: CaptureFailure) {
                resolveError(Exception("RAW capture failed: reason ${failure.reason}"))
            }
        }, handler)
    }

    private fun exifFromDegrees(degrees: Int): Int = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private suspend fun openDevice(cameraId: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { cont.resume(camera) }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(Exception("Camera disconnected"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(Exception("Camera open error $error"))
                }
            }, handler)
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun createMaxResSession(
        device: CameraDevice,
        surface: android.view.Surface,
        executor: Executor
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        // Put the session itself into maximum-resolution mode via session parameters.
        val sessionParams = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
        }.build()

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { cont.resume(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(Exception("Max-res session configuration failed"))
                }
            }
        ).apply { sessionParameters = sessionParams }

        device.createCaptureSession(config)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun captureToFile(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        sensorOrientation: Int,
        settings: CameraSettings,
        outFile: File,
        handler: Handler
    ): Unit = suspendCancellableCoroutine { cont ->
        // Deliver the JPEG bytes straight to disk — never decode a full-res bitmap.
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireNextImage() } catch (e: Exception) { null }
            if (image == null) {
                if (cont.isActive) cont.resumeWithException(Exception("No image produced"))
                return@setOnImageAvailableListener
            }
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                FileOutputStream(outFile).use { it.write(bytes) }
                if (cont.isActive) cont.resume(Unit)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally {
                image.close()
            }
        }, handler)

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation) // app is portrait-locked
            applyManualControls(this, settings)
        }.build()

        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, failure: CaptureFailure) {
                if (cont.isActive) cont.resumeWithException(Exception("Capture failed: reason ${failure.reason}"))
            }
            // Success is signaled by the image arriving on the reader listener above.
        }, handler)
    }

    /** Mirrors ProCameraController.applySettings manual controls onto a Camera2 request. */
    private fun applyManualControls(builder: CaptureRequest.Builder, settings: CameraSettings) {
        // Exposure (ISO + shutter)
        if (settings.iso != CameraSettings.ISO_AUTO) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
            if (settings.shutterSpeed != CameraSettings.SHUTTER_AUTO) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.shutterSpeed)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }

        // Focus
        if (settings.focusDistance != CameraSettings.FOCUS_AUTO) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // White balance
        val awb = when (settings.whiteBalance) {
            WhiteBalanceMode.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
            WhiteBalanceMode.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            WhiteBalanceMode.TUNGSTEN -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
            WhiteBalanceMode.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
            WhiteBalanceMode.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
            else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awb)
    }
}
