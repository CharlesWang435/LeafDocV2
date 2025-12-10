package com.leafdoc.app.stitching

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detects and aligns corn leaf images based on their midrib (central vein).
 *
 * In transmittance imaging of corn leaves, the midrib has a distinct green channel
 * pattern. This class uses green channel analysis to find the midrib position
 * in each image and calculates the vertical offset needed to align all images.
 *
 * The green channel method is more reliable than brightness-based detection
 * because the midrib (central vein) of the leaf has a distinctive green color
 * signature in transmittance imaging.
 */
class MidribAligner {

    /**
     * Result of midrib detection for a single image.
     */
    data class MidribDetectionResult(
        val midribY: Int,           // Y position of detected midrib center
        val confidence: Float,      // How confident we are (0-1)
        val bandWidth: Int          // Estimated width of the midrib band
    )

    /**
     * Detects the midrib position in an image using green channel analysis.
     *
     * The midrib (central vein) of a corn leaf appears as a distinct band
     * when analyzed using the green channel. This method finds the row
     * with the strongest green channel signal relative to other channels.
     *
     * @param bitmap The image to analyze
     * @param searchTolerancePercent How much of the image height to search (0.0-1.0)
     *        e.g., 0.3 means search the middle 30% of the image
     * @return Detection result with midrib Y position
     */
    suspend fun detectMidrib(
        bitmap: Bitmap,
        searchTolerancePercent: Float = 0.5f
    ): MidribDetectionResult = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height

        // Define search region (centered)
        val searchMargin = ((1f - searchTolerancePercent) / 2f * height).toInt()
        val searchStart = searchMargin.coerceAtLeast(0)
        val searchEnd = (height - searchMargin).coerceAtMost(height)

        // Calculate green channel dominance for each row
        // Green dominance = green / (red + green + blue) - measures how "green" each row is
        val rowGreenDominance = FloatArray(height)
        val rowGreenValue = FloatArray(height)
        val pixels = IntArray(width)

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

            var totalGreen = 0f
            var totalSum = 0f

            for (x in 0 until width) {
                val pixel = pixels[x]
                val r = Color.red(pixel).toFloat()
                val g = Color.green(pixel).toFloat()
                val b = Color.blue(pixel).toFloat()

                totalGreen += g
                totalSum += (r + g + b)
            }

            // Green dominance ratio - higher means more green relative to total
            rowGreenDominance[y] = if (totalSum > 0) totalGreen / totalSum else 0f
            rowGreenValue[y] = totalGreen / width
        }

        // Find the band with highest green dominance using a sliding window
        // This helps detect a band rather than a single line
        val bandSize = (height * 0.03f).toInt().coerceAtLeast(3) // 3% of height or min 3 pixels
        var maxBandScore = Float.MIN_VALUE
        var bestBandCenter = height / 2

        for (y in searchStart until (searchEnd - bandSize)) {
            // Calculate band score as combination of green dominance and absolute green value
            var bandGreenDominance = 0f
            var bandGreenValue = 0f

            for (i in 0 until bandSize) {
                bandGreenDominance += rowGreenDominance[y + i]
                bandGreenValue += rowGreenValue[y + i]
            }
            bandGreenDominance /= bandSize
            bandGreenValue /= bandSize

            // Combined score: weight green dominance higher for color-based detection
            val bandScore = bandGreenDominance * 0.6f + (bandGreenValue / 255f) * 0.4f

            if (bandScore > maxBandScore) {
                maxBandScore = bandScore
                bestBandCenter = y + bandSize / 2
            }
        }

        // Calculate confidence based on how distinct the midrib is
        val avgGreenDominance = rowGreenDominance.slice(searchStart until searchEnd).average().toFloat()
        val midribGreenDominance = rowGreenDominance[bestBandCenter]

        val confidence = if (avgGreenDominance > 0) {
            ((midribGreenDominance - avgGreenDominance) / avgGreenDominance)
                .coerceIn(0f, 1f)
        } else {
            0f
        }

        MidribDetectionResult(
            midribY = bestBandCenter,
            confidence = confidence,
            bandWidth = bandSize
        )
    }

    /**
     * Aligns multiple images so their midribs are at the same vertical position.
     *
     * @param images List of images to align
     * @param searchTolerancePercent Search tolerance for midrib detection
     * @param targetY Optional target Y position for midrib. If null, uses the first image's midrib position.
     * @return List of aligned images (new bitmaps, originals are not modified)
     */
    suspend fun alignImages(
        images: List<Bitmap>,
        searchTolerancePercent: Float = 0.5f,
        targetY: Int? = null
    ): List<AlignedImage> = withContext(Dispatchers.Default) {
        if (images.isEmpty()) return@withContext emptyList()

        // Detect midrib in all images
        val detections = images.map { detectMidrib(it, searchTolerancePercent) }

        // Determine target Y position (use first image's midrib or provided target)
        val referenceY = targetY ?: detections.first().midribY

        // Calculate vertical offsets for each image
        val alignedImages = images.mapIndexed { index, bitmap ->
            val detection = detections[index]
            val verticalOffset = referenceY - detection.midribY

            AlignedImage(
                bitmap = bitmap,
                verticalOffset = verticalOffset,
                midribY = detection.midribY,
                confidence = detection.confidence
            )
        }

        alignedImages
    }

    /**
     * Creates a new bitmap with the image shifted vertically to align the midrib.
     * Areas outside the original image are filled with transparency or a specified color.
     *
     * @param bitmap Original bitmap
     * @param verticalOffset How many pixels to shift (positive = down, negative = up)
     * @param fillColor Color to fill empty areas (default: transparent)
     * @return New shifted bitmap
     */
    fun shiftBitmapVertically(
        bitmap: Bitmap,
        verticalOffset: Int,
        fillColor: Int = Color.TRANSPARENT
    ): Bitmap {
        if (verticalOffset == 0) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Fill with background color
        canvas.drawColor(fillColor)

        // Draw shifted image
        canvas.drawBitmap(bitmap, 0f, verticalOffset.toFloat(), null)

        return result
    }

    /**
     * Aligns and creates new bitmaps with midribs aligned.
     * This is the main function to use for the stitching pipeline.
     *
     * @param images Original images
     * @param searchTolerancePercent Search tolerance (0.0-1.0)
     * @param fillColor Color for areas outside original image bounds
     * @return List of new aligned bitmaps (caller is responsible for recycling)
     */
    suspend fun createAlignedBitmaps(
        images: List<Bitmap>,
        searchTolerancePercent: Float = 0.5f,
        fillColor: Int = Color.TRANSPARENT
    ): List<Bitmap> = withContext(Dispatchers.Default) {
        val alignedInfo = alignImages(images, searchTolerancePercent)

        // Find the maximum vertical shift to determine output height
        val maxShiftUp = alignedInfo.minOfOrNull { it.verticalOffset } ?: 0
        val maxShiftDown = alignedInfo.maxOfOrNull { it.verticalOffset } ?: 0

        // Calculate required height to fit all shifted images
        val maxHeight = images.maxOfOrNull { it.height } ?: 0
        val requiredHeight = maxHeight + abs(maxShiftUp) + abs(maxShiftDown)

        // Adjust offsets so no image goes out of bounds at the top
        val offsetAdjustment = if (maxShiftUp < 0) abs(maxShiftUp) else 0

        alignedInfo.map { aligned ->
            val adjustedOffset = aligned.verticalOffset + offsetAdjustment

            // Create expanded bitmap if needed
            if (requiredHeight > aligned.bitmap.height || adjustedOffset != 0) {
                val result = Bitmap.createBitmap(
                    aligned.bitmap.width,
                    requiredHeight,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(result)
                canvas.drawColor(fillColor)
                canvas.drawBitmap(aligned.bitmap, 0f, adjustedOffset.toFloat(), null)
                result
            } else {
                aligned.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            }
        }
    }
}

/**
 * Represents an image with its alignment information.
 */
data class AlignedImage(
    val bitmap: Bitmap,
    val verticalOffset: Int,    // How much to shift vertically to align
    val midribY: Int,           // Detected midrib position
    val confidence: Float       // Detection confidence
)
