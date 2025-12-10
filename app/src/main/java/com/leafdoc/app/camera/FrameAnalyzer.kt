package com.leafdoc.app.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class FrameAnalyzer(
    private val onHistogramReady: (IntArray) -> Unit
) : ImageAnalysis.Analyzer {

    private var frameCounter = 0
    private val analyzeEveryNFrames = 5  // Analyze every 5th frame for performance

    override fun analyze(image: ImageProxy) {
        frameCounter++

        if (frameCounter % analyzeEveryNFrames == 0) {
            val histogram = calculateHistogram(image)
            onHistogramReady(histogram)
        }

        image.close()
    }

    private fun calculateHistogram(image: ImageProxy): IntArray {
        val histogram = IntArray(256)

        when (image.format) {
            ImageFormat.YUV_420_888 -> {
                // Use Y plane for luminance histogram
                val yPlane = image.planes[0]
                val yBuffer = yPlane.buffer
                val yRowStride = yPlane.rowStride
                val yPixelStride = yPlane.pixelStride

                val width = image.width
                val height = image.height

                // Sample every 4th pixel for performance
                val sampleStep = 4

                for (row in 0 until height step sampleStep) {
                    for (col in 0 until width step sampleStep) {
                        val index = row * yRowStride + col * yPixelStride
                        if (index < yBuffer.capacity()) {
                            val luminance = yBuffer.get(index).toInt() and 0xFF
                            histogram[luminance]++
                        }
                    }
                }
            }
            else -> {
                // Fallback: create empty histogram
            }
        }

        return histogram
    }
}

class ZebraAnalyzer(
    private val threshold: Int = 95,
    private val onZebraUpdate: (List<OverexposedRegion>) -> Unit
) : ImageAnalysis.Analyzer {

    private var frameCounter = 0
    private val analyzeEveryNFrames = 10

    override fun analyze(image: ImageProxy) {
        frameCounter++

        if (frameCounter % analyzeEveryNFrames == 0) {
            val regions = findOverexposedRegions(image)
            onZebraUpdate(regions)
        }

        image.close()
    }

    private fun findOverexposedRegions(image: ImageProxy): List<OverexposedRegion> {
        val regions = mutableListOf<OverexposedRegion>()

        if (image.format != ImageFormat.YUV_420_888) {
            return regions
        }

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride

        val width = image.width
        val height = image.height

        val thresholdValue = (threshold * 255) / 100
        val blockSize = 32

        for (blockY in 0 until height step blockSize) {
            for (blockX in 0 until width step blockSize) {
                var overexposedCount = 0
                var totalCount = 0

                for (row in blockY until minOf(blockY + blockSize, height)) {
                    for (col in blockX until minOf(blockX + blockSize, width)) {
                        val index = row * yRowStride + col
                        if (index < yBuffer.capacity()) {
                            val luminance = yBuffer.get(index).toInt() and 0xFF
                            if (luminance > thresholdValue) {
                                overexposedCount++
                            }
                            totalCount++
                        }
                    }
                }

                if (totalCount > 0 && overexposedCount.toFloat() / totalCount > 0.5f) {
                    regions.add(
                        OverexposedRegion(
                            x = blockX.toFloat() / width,
                            y = blockY.toFloat() / height,
                            width = blockSize.toFloat() / width,
                            height = blockSize.toFloat() / height
                        )
                    )
                }
            }
        }

        return regions
    }
}

data class OverexposedRegion(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

class FocusPeakingAnalyzer(
    private val sensitivity: Float = 0.5f,
    private val onFocusPeakingUpdate: (List<FocusPeakPoint>) -> Unit
) : ImageAnalysis.Analyzer {

    private var frameCounter = 0
    private val analyzeEveryNFrames = 8

    override fun analyze(image: ImageProxy) {
        frameCounter++

        if (frameCounter % analyzeEveryNFrames == 0) {
            val peakPoints = detectEdges(image)
            onFocusPeakingUpdate(peakPoints)
        }

        image.close()
    }

    private fun detectEdges(image: ImageProxy): List<FocusPeakPoint> {
        val points = mutableListOf<FocusPeakPoint>()

        if (image.format != ImageFormat.YUV_420_888) {
            return points
        }

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride

        val width = image.width
        val height = image.height

        val threshold = ((1 - sensitivity) * 100).toInt()
        val sampleStep = 8

        // Simple Sobel edge detection
        for (row in sampleStep until height - sampleStep step sampleStep) {
            for (col in sampleStep until width - sampleStep step sampleStep) {
                val gradientX = getPixel(yBuffer, row, col + 1, yRowStride) -
                        getPixel(yBuffer, row, col - 1, yRowStride)
                val gradientY = getPixel(yBuffer, row + 1, col, yRowStride) -
                        getPixel(yBuffer, row - 1, col, yRowStride)

                val gradient = kotlin.math.sqrt((gradientX * gradientX + gradientY * gradientY).toDouble()).toInt()

                if (gradient > threshold) {
                    points.add(
                        FocusPeakPoint(
                            x = col.toFloat() / width,
                            y = row.toFloat() / height,
                            intensity = (gradient.toFloat() / 255).coerceIn(0f, 1f)
                        )
                    )
                }
            }
        }

        return points
    }

    private fun getPixel(buffer: java.nio.ByteBuffer, row: Int, col: Int, rowStride: Int): Int {
        val index = row * rowStride + col
        return if (index < buffer.capacity()) {
            buffer.get(index).toInt() and 0xFF
        } else {
            0
        }
    }
}

data class FocusPeakPoint(
    val x: Float,
    val y: Float,
    val intensity: Float
)
