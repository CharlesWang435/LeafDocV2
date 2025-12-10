package com.leafdoc.app.stitching

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides visual guidance for capturing overlapping leaf segments.
 * Shows the previous segment edge to help users align the next capture.
 */
class OverlapGuide {

    private var previousSegmentEdge: Bitmap? = null
    private var overlapPercentage: Float = 0.25f

    /**
     * Sets the previous segment for overlap guidance.
     */
    suspend fun setPreviousSegment(
        bitmap: Bitmap,
        overlapPercent: Float = 0.25f
    ) = withContext(Dispatchers.Default) {
        overlapPercentage = overlapPercent

        // Extract the right edge of the previous segment
        val overlapWidth = (bitmap.width * overlapPercent).toInt().coerceAtLeast(1)

        previousSegmentEdge?.recycle()
        previousSegmentEdge = Bitmap.createBitmap(
            bitmap,
            bitmap.width - overlapWidth,
            0,
            overlapWidth,
            bitmap.height
        )
    }

    /**
     * Creates an overlay bitmap showing the overlap guide.
     */
    suspend fun createOverlayGuide(
        previewWidth: Int,
        previewHeight: Int,
        guideOpacity: Float = 0.4f
    ): Bitmap? = withContext(Dispatchers.Default) {
        val edge = previousSegmentEdge ?: return@withContext null

        val overlay = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(overlay)

        // Scale the edge to match preview height
        val scaledHeight = previewHeight
        val scale = scaledHeight.toFloat() / edge.height
        val scaledWidth = (edge.width * scale).toInt()

        val scaledEdge = Bitmap.createScaledBitmap(edge, scaledWidth, scaledHeight, true)

        // Apply transparency
        val paint = Paint().apply {
            alpha = (guideOpacity * 255).toInt()
        }

        // Draw on left side of preview (where overlap should be)
        canvas.drawBitmap(scaledEdge, 0f, 0f, paint)

        // Draw guide lines
        val linePaint = Paint().apply {
            color = Color.argb(200, 0, 255, 0)  // Green guide line
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }

        // Vertical line showing overlap boundary
        canvas.drawLine(
            scaledWidth.toFloat(),
            0f,
            scaledWidth.toFloat(),
            previewHeight.toFloat(),
            linePaint
        )

        // Dashed lines for alignment
        linePaint.apply {
            color = Color.argb(150, 255, 255, 0)  // Yellow
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
        }

        // Horizontal center line
        canvas.drawLine(
            0f,
            previewHeight / 2f,
            previewWidth.toFloat(),
            previewHeight / 2f,
            linePaint
        )

        scaledEdge.recycle()

        overlay
    }

    /**
     * Calculates alignment score between current preview and previous segment.
     * Returns 0-100 where 100 is perfect alignment.
     */
    suspend fun calculateAlignmentScore(
        currentFrame: Bitmap
    ): Int = withContext(Dispatchers.Default) {
        val edge = previousSegmentEdge ?: return@withContext 100

        // Extract left edge of current frame
        val overlapWidth = (currentFrame.width * overlapPercentage).toInt().coerceAtLeast(1)
        val currentEdge = try {
            Bitmap.createBitmap(currentFrame, 0, 0, overlapWidth, currentFrame.height)
        } catch (e: Exception) {
            return@withContext 50
        }

        // Scale edges to same size for comparison
        val compareHeight = minOf(edge.height, currentEdge.height, 200)
        val compareWidth = minOf(edge.width, currentEdge.width, 100)

        val scaledPrevious = Bitmap.createScaledBitmap(edge, compareWidth, compareHeight, true)
        val scaledCurrent = Bitmap.createScaledBitmap(currentEdge, compareWidth, compareHeight, true)

        var matchScore = 0.0
        var totalPixels = 0

        for (y in 0 until compareHeight step 2) {
            for (x in 0 until compareWidth step 2) {
                val prevPixel = scaledPrevious.getPixel(x, y)
                val currPixel = scaledCurrent.getPixel(x, y)

                val prevGray = (Color.red(prevPixel) + Color.green(prevPixel) + Color.blue(prevPixel)) / 3
                val currGray = (Color.red(currPixel) + Color.green(currPixel) + Color.blue(currPixel)) / 3

                val diff = kotlin.math.abs(prevGray - currGray)
                matchScore += (255 - diff)
                totalPixels++
            }
        }

        currentEdge.recycle()
        scaledPrevious.recycle()
        scaledCurrent.recycle()

        if (totalPixels == 0) return@withContext 50

        ((matchScore / totalPixels / 255) * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Creates alignment indicator graphics.
     */
    fun createAlignmentIndicator(
        score: Int,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val color = when {
            score >= 80 -> Color.GREEN
            score >= 60 -> Color.YELLOW
            score >= 40 -> Color.rgb(255, 165, 0)  // Orange
            else -> Color.RED
        }

        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Draw circular indicator
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 3f

        // Background circle
        paint.alpha = 50
        canvas.drawCircle(centerX, centerY, radius, paint)

        // Progress arc
        paint.alpha = 200
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius / 4

        val sweepAngle = score * 3.6f  // 360 degrees = 100%
        canvas.drawArc(
            RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius),
            -90f,
            sweepAngle,
            false,
            paint
        )

        // Score text
        paint.style = Paint.Style.FILL
        paint.textSize = radius / 2
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE

        canvas.drawText(
            "$score%",
            centerX,
            centerY + paint.textSize / 3,
            paint
        )

        return bitmap
    }

    /**
     * Clears the overlap guide.
     */
    fun clear() {
        previousSegmentEdge?.recycle()
        previousSegmentEdge = null
    }

    /**
     * Releases resources.
     */
    fun release() {
        clear()
    }
}

/**
 * Data class for overlap guide state.
 */
data class OverlapGuideState(
    val hasGuidе: Boolean = false,
    val overlapPercentage: Float = 0.25f,
    val alignmentScore: Int = 0,
    val isAligned: Boolean = false
) {
    val alignmentStatus: AlignmentStatus
        get() = when {
            !hasGuidе -> AlignmentStatus.NO_PREVIOUS
            alignmentScore >= 80 -> AlignmentStatus.EXCELLENT
            alignmentScore >= 60 -> AlignmentStatus.GOOD
            alignmentScore >= 40 -> AlignmentStatus.FAIR
            else -> AlignmentStatus.POOR
        }
}

enum class AlignmentStatus(val displayText: String, val color: Int) {
    NO_PREVIOUS("Ready to capture", Color.GRAY),
    EXCELLENT("Excellent alignment", Color.GREEN),
    GOOD("Good alignment", Color.GREEN),
    FAIR("Adjust position", Color.YELLOW),
    POOR("Poor alignment", Color.RED)
}
