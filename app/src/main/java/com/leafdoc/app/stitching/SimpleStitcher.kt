package com.leafdoc.app.stitching

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Simple image stitcher for horizontal concatenation of leaf segments.
 *
 * This stitcher is designed for the LeafDoc capture workflow where:
 * 1. User captures segments from base to tip of the leaf
 * 2. Each segment has a small overlap (~10%) with the previous one
 * 3. Segments are stitched left-to-right with gradient blending in overlap zones
 *
 * Optionally performs midrib alignment to correct vertical drift between segments.
 */
class SimpleStitcher {

    private val midribAligner = MidribAligner()

    /**
     * Stitches multiple images horizontally with overlap blending.
     *
     * @param images List of cropped bitmap segments, in order from left to right (base to tip)
     * @param overlapPercent The percentage of overlap between consecutive images (0.0-1.0)
     * @param alignMidrib Whether to auto-align images based on midrib detection (ignored if manualOffsets provided)
     * @param midribSearchTolerance How much of image height to search for midrib (0.0-1.0)
     * @param manualOffsets Optional list of manual Y offsets (in pixels) for each image.
     *                      Positive = shift down, Negative = shift up. First image should have offset 0.
     *                      If provided, overrides alignMidrib.
     * @return StitchResult containing the stitched panorama or an error
     */
    suspend fun stitchImages(
        images: List<Bitmap>,
        overlapPercent: Float = 0.10f,
        alignMidrib: Boolean = false,
        midribSearchTolerance: Float = 0.5f,
        manualOffsets: List<Int>? = null
    ): StitchResult = withContext(Dispatchers.Default) {
        if (images.isEmpty()) {
            return@withContext StitchResult.Error("No images to stitch")
        }

        if (images.size == 1) {
            return@withContext StitchResult.Success(
                images.first().copy(Bitmap.Config.ARGB_8888, true)
            )
        }

        try {
            // Determine if we need to align images
            val needsAlignment = manualOffsets != null || alignMidrib

            // Apply alignment: manual offsets take priority over auto midrib alignment
            val processedImages = when {
                manualOffsets != null && manualOffsets.size == images.size -> {
                    applyManualOffsets(images, manualOffsets, Color.WHITE)
                }
                alignMidrib -> {
                    midribAligner.createAlignedBitmaps(
                        images = images,
                        searchTolerancePercent = midribSearchTolerance,
                        fillColor = Color.WHITE // Use white for transmittance background
                    )
                }
                else -> {
                    images.map { it.copy(Bitmap.Config.ARGB_8888, true) }
                }
            }

            try {
                // Calculate total width needed
                val overlapWidth = (processedImages.first().width * overlapPercent).toInt()
                var totalWidth = processedImages.first().width

                for (i in 1 until processedImages.size) {
                    // Add width minus overlap for each subsequent image
                    totalWidth += processedImages[i].width - overlapWidth
                }

                // Use the maximum height among all images
                val maxHeight = processedImages.maxOf { it.height }

                // Create result bitmap
                val result = Bitmap.createBitmap(totalWidth, maxHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)

                // Fill with white background (for transmittance imaging)
                canvas.drawColor(Color.WHITE)

                // Draw first image (no left blend)
                canvas.drawBitmap(processedImages.first(), 0f, 0f, null)

                var currentX = processedImages.first().width - overlapWidth

                // Draw subsequent images with blending
                for (i in 1 until processedImages.size) {
                    val currentImage = processedImages[i]
                    val isLastImage = i == processedImages.size - 1

                    // Blend the overlap region
                    blendAndDraw(
                        canvas = canvas,
                        previousImage = processedImages[i - 1],
                        currentImage = currentImage,
                        xPosition = currentX,
                        overlapWidth = overlapWidth,
                        isLastImage = isLastImage
                    )

                    currentX += currentImage.width - overlapWidth
                }

                // Recycle processed images if they were created (aligned or copied)
                if (needsAlignment) {
                    processedImages.forEach { it.recycle() }
                }

                StitchResult.Success(result)
            } catch (e: Exception) {
                // Clean up on error
                if (needsAlignment) {
                    processedImages.forEach { it.recycle() }
                }
                throw e
            }
        } catch (e: Exception) {
            StitchResult.Error(e.message ?: "Stitching failed")
        }
    }

    /**
     * Blends and draws the current image onto the canvas.
     * The left edge of the current image is blended with the right edge of the previous image.
     */
    private fun blendAndDraw(
        canvas: Canvas,
        previousImage: Bitmap,
        currentImage: Bitmap,
        xPosition: Int,
        overlapWidth: Int,
        isLastImage: Boolean
    ) {
        val height = currentImage.height
        val actualOverlap = minOf(overlapWidth, currentImage.width, previousImage.width)

        if (actualOverlap <= 0) {
            // No overlap, just draw the image
            canvas.drawBitmap(currentImage, xPosition.toFloat(), 0f, null)
            return
        }

        // Create blended overlap region
        val blendedOverlap = createBlendedOverlap(
            previousImage = previousImage,
            currentImage = currentImage,
            overlapWidth = actualOverlap
        )

        // Draw the blended overlap region
        canvas.drawBitmap(blendedOverlap, xPosition.toFloat(), 0f, null)
        blendedOverlap.recycle()

        // Draw the non-overlapping part of current image (right portion)
        if (actualOverlap < currentImage.width) {
            val nonOverlapPart = Bitmap.createBitmap(
                currentImage,
                actualOverlap,
                0,
                currentImage.width - actualOverlap,
                height
            )
            canvas.drawBitmap(
                nonOverlapPart,
                (xPosition + actualOverlap).toFloat(),
                0f,
                null
            )
            nonOverlapPart.recycle()
        }
    }

    /**
     * Creates a blended bitmap for the overlap region using linear gradient blending.
     */
    private fun createBlendedOverlap(
        previousImage: Bitmap,
        currentImage: Bitmap,
        overlapWidth: Int
    ): Bitmap {
        val height = maxOf(previousImage.height, currentImage.height)
        val blended = Bitmap.createBitmap(overlapWidth, height, Bitmap.Config.ARGB_8888)

        // Extract pixels from both images for the overlap region
        val prevStartX = previousImage.width - overlapWidth
        val prevPixels = IntArray(overlapWidth * previousImage.height)
        val currPixels = IntArray(overlapWidth * currentImage.height)

        previousImage.getPixels(
            prevPixels, 0, overlapWidth,
            prevStartX, 0, overlapWidth, previousImage.height
        )
        currentImage.getPixels(
            currPixels, 0, overlapWidth,
            0, 0, overlapWidth, currentImage.height
        )

        val blendedPixels = IntArray(overlapWidth * height)

        for (x in 0 until overlapWidth) {
            // Linear blend factor: 0 at left edge (previous image), 1 at right edge (current image)
            val blendFactor = x.toFloat() / overlapWidth

            for (y in 0 until height) {
                val prevPixel = if (y < previousImage.height) {
                    prevPixels[y * overlapWidth + x]
                } else {
                    Color.TRANSPARENT
                }

                val currPixel = if (y < currentImage.height) {
                    currPixels[y * overlapWidth + x]
                } else {
                    Color.TRANSPARENT
                }

                blendedPixels[y * overlapWidth + x] = when {
                    prevPixel != Color.TRANSPARENT && currPixel != Color.TRANSPARENT ->
                        blendColors(prevPixel, currPixel, blendFactor)
                    prevPixel != Color.TRANSPARENT -> prevPixel
                    currPixel != Color.TRANSPARENT -> currPixel
                    else -> Color.TRANSPARENT
                }
            }
        }

        blended.setPixels(blendedPixels, 0, overlapWidth, 0, 0, overlapWidth, height)
        return blended
    }

    /**
     * Blends two colors using linear interpolation.
     * factor = 0 means 100% color1, factor = 1 means 100% color2
     */
    private fun blendColors(color1: Int, color2: Int, factor: Float): Int {
        val r = ((1 - factor) * Color.red(color1) + factor * Color.red(color2)).toInt()
        val g = ((1 - factor) * Color.green(color1) + factor * Color.green(color2)).toInt()
        val b = ((1 - factor) * Color.blue(color1) + factor * Color.blue(color2)).toInt()
        val a = ((1 - factor) * Color.alpha(color1) + factor * Color.alpha(color2)).toInt()
        return Color.argb(a, r, g, b)
    }

    /**
     * Applies manual vertical offsets to a list of images.
     * Each image is shifted vertically according to its offset.
     * Positive offset = shift down, Negative = shift up.
     *
     * @param images Original images
     * @param offsets List of vertical offsets (one per image)
     * @param fillColor Color to fill empty areas (default: white for transmittance)
     * @return List of new shifted bitmaps (caller is responsible for recycling)
     */
    private fun applyManualOffsets(
        images: List<Bitmap>,
        offsets: List<Int>,
        fillColor: Int = Color.WHITE
    ): List<Bitmap> {
        if (images.isEmpty() || offsets.isEmpty()) return emptyList()

        // Find the range of offsets to determine required height expansion
        val minOffset = offsets.minOrNull() ?: 0
        val maxOffset = offsets.maxOrNull() ?: 0
        val maxOriginalHeight = images.maxOfOrNull { it.height } ?: 0

        // Calculate required height to fit all shifted images
        // If minOffset is negative (shift up), we need extra space at top
        // If maxOffset is positive (shift down), we need extra space at bottom
        val extraTop = if (minOffset < 0) abs(minOffset) else 0
        val extraBottom = if (maxOffset > 0) maxOffset else 0
        val requiredHeight = maxOriginalHeight + extraTop + extraBottom

        return images.mapIndexed { index, bitmap ->
            val offset = offsets.getOrElse(index) { 0 }

            // Adjust offset so no image goes out of bounds at the top
            val adjustedOffset = offset + extraTop

            val result = Bitmap.createBitmap(bitmap.width, requiredHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(fillColor)
            canvas.drawBitmap(bitmap, 0f, adjustedOffset.toFloat(), null)
            result
        }
    }

    /**
     * Creates a quick preview of the stitched result with manual offsets applied.
     * This is useful for the manual alignment screen to show a live preview.
     *
     * @param images List of images to stitch
     * @param offsets Manual Y offsets for each image
     * @param overlapPercent Overlap percentage between images
     * @param scale Scale factor for the preview (0.0-1.0). Lower = faster but smaller.
     * @return Preview bitmap or null on error
     */
    suspend fun createPreview(
        images: List<Bitmap>,
        offsets: List<Int>,
        overlapPercent: Float = 0.10f,
        scale: Float = 0.25f
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (images.isEmpty()) return@withContext null
        if (images.size == 1) {
            return@withContext Bitmap.createScaledBitmap(
                images.first(),
                (images.first().width * scale).toInt().coerceAtLeast(1),
                (images.first().height * scale).toInt().coerceAtLeast(1),
                true
            )
        }

        try {
            // Scale down images first for faster processing
            val scaledImages = images.map { bitmap ->
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            }

            // Scale offsets proportionally
            val scaledOffsets = offsets.map { (it * scale).toInt() }

            // Apply offsets to scaled images
            val processedImages = applyManualOffsets(scaledImages, scaledOffsets, Color.WHITE)

            // Clean up scaled images
            scaledImages.forEach { it.recycle() }

            // Calculate total width for preview
            val scaledOverlapWidth = (processedImages.first().width * overlapPercent).toInt()
            var totalWidth = processedImages.first().width
            for (i in 1 until processedImages.size) {
                totalWidth += processedImages[i].width - scaledOverlapWidth
            }

            val maxHeight = processedImages.maxOf { it.height }

            // Create preview bitmap
            val preview = Bitmap.createBitmap(totalWidth, maxHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(preview)
            canvas.drawColor(Color.WHITE)

            // Draw first image
            canvas.drawBitmap(processedImages.first(), 0f, 0f, null)

            var currentX = processedImages.first().width - scaledOverlapWidth

            // Draw subsequent images with blending
            for (i in 1 until processedImages.size) {
                val currentImage = processedImages[i]
                blendAndDraw(
                    canvas = canvas,
                    previousImage = processedImages[i - 1],
                    currentImage = currentImage,
                    xPosition = currentX,
                    overlapWidth = scaledOverlapWidth,
                    isLastImage = i == processedImages.size - 1
                )
                currentX += currentImage.width - scaledOverlapWidth
            }

            // Clean up processed images
            processedImages.forEach { it.recycle() }

            preview
        } catch (e: Exception) {
            null
        }
    }
}
