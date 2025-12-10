package com.leafdoc.app.stitching

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Image stitching engine for combining leaf segment images.
 * Uses feature matching and blending to create seamless panoramas.
 *
 * Since the leaf enters horizontally and the phone is vertical,
 * segments are stitched horizontally (left to right).
 */
class ImageStitcher {

    private val featureDetector = SimpleFeatureDetector()
    private val blender = ImageBlender()

    /**
     * Stitches multiple images together horizontally.
     * Images should have overlapping regions for best results.
     *
     * @param images List of bitmaps to stitch, in order from left to right
     * @param overlapPercentage Expected overlap between consecutive images (0.0-1.0)
     * @return Stitched panorama bitmap
     */
    suspend fun stitchImages(
        images: List<Bitmap>,
        overlapPercentage: Float = 0.25f
    ): StitchResult = withContext(Dispatchers.Default) {
        if (images.isEmpty()) {
            return@withContext StitchResult.Error("No images to stitch")
        }

        if (images.size == 1) {
            return@withContext StitchResult.Success(images.first().copy(Bitmap.Config.ARGB_8888, true))
        }

        var result: Bitmap? = null
        try {
            result = images.first().copy(Bitmap.Config.ARGB_8888, true)

            for (i in 1 until images.size) {
                val nextImage = images[i]
                val stitchPair = stitchPair(result!!, nextImage, overlapPercentage)

                when (stitchPair) {
                    is StitchResult.Success -> {
                        result.recycle()
                        result = stitchPair.bitmap
                    }
                    is StitchResult.Error -> {
                        // If feature matching fails, use simple concatenation
                        val concatenated = simpleConcatenate(result!!, nextImage, overlapPercentage)
                        result.recycle()
                        result = concatenated
                    }
                    is StitchResult.Progress -> { /* Not applicable here */ }
                }
            }

            StitchResult.Success(result!!)
        } catch (e: Exception) {
            // Clean up bitmap on exception to prevent memory leak
            result?.recycle()
            StitchResult.Error(e.message ?: "Stitching failed")
        }
    }

    /**
     * Stitches two images together.
     */
    private suspend fun stitchPair(
        left: Bitmap,
        right: Bitmap,
        overlapPercentage: Float
    ): StitchResult = withContext(Dispatchers.Default) {
        try {
            // Calculate expected overlap region
            val overlapWidth = (min(left.width, right.width) * overlapPercentage).toInt()

            // Extract overlap regions
            val leftOverlap = Bitmap.createBitmap(
                left,
                left.width - overlapWidth,
                0,
                overlapWidth,
                left.height
            )

            val rightOverlap = Bitmap.createBitmap(
                right,
                0,
                0,
                overlapWidth,
                right.height
            )

            // Find best alignment using correlation
            val alignment = findBestAlignment(leftOverlap, rightOverlap)

            leftOverlap.recycle()
            rightOverlap.recycle()

            // Create stitched result
            val stitchedWidth = left.width + right.width - overlapWidth + alignment.xOffset
            val stitchedHeight = max(left.height, right.height + abs(alignment.yOffset))

            val stitched = Bitmap.createBitmap(
                stitchedWidth.coerceAtLeast(1),
                stitchedHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(stitched)

            // Draw left image
            val leftYOffset = if (alignment.yOffset < 0) abs(alignment.yOffset) else 0
            canvas.drawBitmap(left, 0f, leftYOffset.toFloat(), null)

            // Draw right image with blending in overlap region
            val rightXOffset = left.width - overlapWidth + alignment.xOffset
            val rightYOffset = if (alignment.yOffset > 0) alignment.yOffset else 0

            // Blend overlap region
            blendOverlapRegion(
                canvas,
                left,
                right,
                rightXOffset,
                rightYOffset,
                leftYOffset,
                overlapWidth - alignment.xOffset
            )

            // Draw non-overlapping part of right image
            val nonOverlapX = rightXOffset + overlapWidth - alignment.xOffset
            if (nonOverlapX < right.width) {
                val nonOverlapPart = Bitmap.createBitmap(
                    right,
                    overlapWidth - alignment.xOffset,
                    0,
                    right.width - (overlapWidth - alignment.xOffset),
                    right.height
                )
                canvas.drawBitmap(nonOverlapPart, nonOverlapX.toFloat(), rightYOffset.toFloat(), null)
                nonOverlapPart.recycle()
            }

            StitchResult.Success(stitched)
        } catch (e: Exception) {
            StitchResult.Error(e.message ?: "Pair stitching failed")
        }
    }

    /**
     * Finds the best alignment between two overlap regions using normalized cross-correlation.
     */
    private fun findBestAlignment(left: Bitmap, right: Bitmap): Alignment {
        val searchRangeX = 20
        val searchRangeY = 50

        var bestCorrelation = Float.MIN_VALUE
        var bestX = 0
        var bestY = 0

        // Downsample for faster processing
        val scale = 4
        val leftSmall = Bitmap.createScaledBitmap(
            left,
            left.width / scale,
            left.height / scale,
            true
        )
        val rightSmall = Bitmap.createScaledBitmap(
            right,
            right.width / scale,
            right.height / scale,
            true
        )

        for (dy in -searchRangeY / scale..searchRangeY / scale) {
            for (dx in -searchRangeX / scale..searchRangeX / scale) {
                val correlation = calculateCorrelation(leftSmall, rightSmall, dx, dy)
                if (correlation > bestCorrelation) {
                    bestCorrelation = correlation
                    bestX = dx * scale
                    bestY = dy * scale
                }
            }
        }

        leftSmall.recycle()
        rightSmall.recycle()

        return Alignment(bestX, bestY, bestCorrelation)
    }

    /**
     * Calculates normalized cross-correlation between two images with offset.
     */
    private fun calculateCorrelation(left: Bitmap, right: Bitmap, dx: Int, dy: Int): Float {
        var sum = 0.0
        var count = 0

        val startY = max(0, dy)
        val endY = min(left.height, right.height + dy)
        val startX = max(0, dx)
        val endX = min(left.width, right.width + dx)

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                val leftPixel = left.getPixel(x, y)
                val rightX = x - dx
                val rightY = y - dy

                if (rightX >= 0 && rightX < right.width && rightY >= 0 && rightY < right.height) {
                    val rightPixel = right.getPixel(rightX, rightY)

                    val leftGray = (Color.red(leftPixel) + Color.green(leftPixel) + Color.blue(leftPixel)) / 3
                    val rightGray = (Color.red(rightPixel) + Color.green(rightPixel) + Color.blue(rightPixel)) / 3

                    sum += (255 - abs(leftGray - rightGray))
                    count++
                }
            }
        }

        return if (count > 0) (sum / count).toFloat() else 0f
    }

    /**
     * Blends the overlap region using linear gradient blending.
     * Optimized to use pixel arrays instead of individual drawPoint calls.
     */
    private fun blendOverlapRegion(
        canvas: Canvas,
        left: Bitmap,
        right: Bitmap,
        rightXOffset: Int,
        rightYOffset: Int,
        leftYOffset: Int,
        overlapWidth: Int
    ) {
        if (overlapWidth <= 0) return

        val blendHeight = max(left.height + leftYOffset, right.height + rightYOffset)
        val clampedOverlapWidth = min(overlapWidth, min(left.width - rightXOffset, right.width))

        if (clampedOverlapWidth <= 0 || blendHeight <= 0) return

        // Create a bitmap for the blended region
        val blendedRegion = Bitmap.createBitmap(clampedOverlapWidth, blendHeight, Bitmap.Config.ARGB_8888)
        val blendedPixels = IntArray(clampedOverlapWidth * blendHeight)

        // Pre-extract pixels from source bitmaps for faster access
        val leftPixels = IntArray(left.width * left.height)
        val rightPixels = IntArray(right.width * right.height)
        left.getPixels(leftPixels, 0, left.width, 0, 0, left.width, left.height)
        right.getPixels(rightPixels, 0, right.width, 0, 0, right.width, right.height)

        for (x in 0 until clampedOverlapWidth) {
            val blendFactor = x.toFloat() / clampedOverlapWidth
            val leftX = rightXOffset + x

            for (y in 0 until blendHeight) {
                val leftY = y - leftYOffset
                val rightY = y - rightYOffset
                val blendedIdx = y * clampedOverlapWidth + x

                val leftPixel = if (leftX >= 0 && leftX < left.width && leftY >= 0 && leftY < left.height) {
                    leftPixels[leftY * left.width + leftX]
                } else {
                    Color.TRANSPARENT
                }

                val rightPixel = if (x >= 0 && x < right.width && rightY >= 0 && rightY < right.height) {
                    rightPixels[rightY * right.width + x]
                } else {
                    Color.TRANSPARENT
                }

                blendedPixels[blendedIdx] = when {
                    leftPixel != Color.TRANSPARENT && rightPixel != Color.TRANSPARENT ->
                        blendColors(leftPixel, rightPixel, blendFactor)
                    leftPixel != Color.TRANSPARENT -> leftPixel
                    rightPixel != Color.TRANSPARENT -> rightPixel
                    else -> Color.TRANSPARENT
                }
            }
        }

        // Set all pixels at once and draw the blended bitmap
        blendedRegion.setPixels(blendedPixels, 0, clampedOverlapWidth, 0, 0, clampedOverlapWidth, blendHeight)
        canvas.drawBitmap(blendedRegion, rightXOffset.toFloat(), 0f, null)
        blendedRegion.recycle()
    }

    /**
     * Blends two colors with linear interpolation.
     */
    private fun blendColors(color1: Int, color2: Int, factor: Float): Int {
        val r = ((1 - factor) * Color.red(color1) + factor * Color.red(color2)).toInt()
        val g = ((1 - factor) * Color.green(color1) + factor * Color.green(color2)).toInt()
        val b = ((1 - factor) * Color.blue(color1) + factor * Color.blue(color2)).toInt()
        val a = ((1 - factor) * Color.alpha(color1) + factor * Color.alpha(color2)).toInt()

        return Color.argb(a, r, g, b)
    }

    /**
     * Simple horizontal concatenation with basic overlap blending.
     * Fallback when feature matching fails.
     * Optimized to use pixel arrays instead of individual drawPoint calls.
     */
    private fun simpleConcatenate(
        left: Bitmap,
        right: Bitmap,
        overlapPercentage: Float
    ): Bitmap {
        val overlapWidth = (min(left.width, right.width) * overlapPercentage).toInt()
        val resultWidth = left.width + right.width - overlapWidth
        val resultHeight = max(left.height, right.height)

        val result = Bitmap.createBitmap(resultWidth, resultHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Draw left image
        canvas.drawBitmap(left, 0f, 0f, null)

        if (overlapWidth > 0 && right.height > 0) {
            // Create overlap blend region using pixel arrays
            val overlapBitmap = Bitmap.createBitmap(overlapWidth, right.height, Bitmap.Config.ARGB_8888)
            val overlapPixels = IntArray(overlapWidth * right.height)

            // Extract left and right pixels for the overlap region
            val leftPixels = IntArray(left.width * left.height)
            val rightPixels = IntArray(right.width * right.height)
            left.getPixels(leftPixels, 0, left.width, 0, 0, left.width, left.height)
            right.getPixels(rightPixels, 0, right.width, 0, 0, right.width, right.height)

            val leftStartX = left.width - overlapWidth

            for (x in 0 until overlapWidth) {
                val blendFactor = x.toFloat() / overlapWidth
                val leftX = leftStartX + x

                for (y in 0 until right.height) {
                    val leftPixel = if (leftX >= 0 && leftX < left.width && y < left.height) {
                        leftPixels[y * left.width + leftX]
                    } else {
                        Color.TRANSPARENT
                    }

                    val rightPixel = rightPixels[y * right.width + x]

                    // Blend: transition from left to right
                    overlapPixels[y * overlapWidth + x] = blendColors(leftPixel, rightPixel, blendFactor)
                }
            }

            overlapBitmap.setPixels(overlapPixels, 0, overlapWidth, 0, 0, overlapWidth, right.height)
            canvas.drawBitmap(overlapBitmap, leftStartX.toFloat(), 0f, null)
            overlapBitmap.recycle()
        }

        // Draw remaining part of right image
        if (overlapWidth < right.width) {
            val remainingPart = Bitmap.createBitmap(
                right,
                overlapWidth,
                0,
                right.width - overlapWidth,
                right.height
            )
            canvas.drawBitmap(remainingPart, left.width.toFloat(), 0f, null)
            remainingPart.recycle()
        }

        return result
    }

    /**
     * Calculates the overlap percentage between two images.
     */
    suspend fun calculateOverlap(
        previous: Bitmap,
        current: Bitmap
    ): Float = withContext(Dispatchers.Default) {
        // Use correlation to estimate overlap
        val testOverlaps = listOf(0.1f, 0.15f, 0.2f, 0.25f, 0.3f, 0.35f, 0.4f)
        var bestOverlap = 0.25f
        var bestScore = Float.MIN_VALUE

        for (overlap in testOverlaps) {
            val overlapWidth = (min(previous.width, current.width) * overlap).toInt()

            if (overlapWidth <= 0) continue

            val leftOverlap = try {
                Bitmap.createBitmap(
                    previous,
                    previous.width - overlapWidth,
                    0,
                    overlapWidth,
                    previous.height
                )
            } catch (e: Exception) {
                continue
            }

            val rightOverlap = try {
                Bitmap.createBitmap(
                    current,
                    0,
                    0,
                    overlapWidth,
                    current.height
                )
            } catch (e: Exception) {
                leftOverlap.recycle()
                continue
            }

            val score = calculateCorrelation(leftOverlap, rightOverlap, 0, 0)

            leftOverlap.recycle()
            rightOverlap.recycle()

            if (score > bestScore) {
                bestScore = score
                bestOverlap = overlap
            }
        }

        bestOverlap
    }
}

sealed class StitchResult {
    data class Success(val bitmap: Bitmap) : StitchResult()
    data class Error(val message: String) : StitchResult()
    data class Progress(val percentage: Int, val currentPair: Int, val totalPairs: Int) : StitchResult()
}

private data class Alignment(
    val xOffset: Int,
    val yOffset: Int,
    val confidence: Float
)

/**
 * Simple feature detector for alignment assistance.
 */
private class SimpleFeatureDetector {
    // Placeholder for more advanced feature detection if needed
}

/**
 * Image blender for seamless transitions.
 */
private class ImageBlender {
    // Placeholder for more advanced blending techniques
}
