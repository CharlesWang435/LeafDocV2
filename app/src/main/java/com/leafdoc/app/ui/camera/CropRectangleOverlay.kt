package com.leafdoc.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Data class representing the crop rectangle bounds as fractions of the total size (0.0 to 1.0)
 */
data class CropRect(
    val left: Float = 0.1f,
    val top: Float = 0.2f,
    val right: Float = 0.9f,
    val bottom: Float = 0.8f
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun toRect(containerWidth: Float, containerHeight: Float): Rect {
        return Rect(
            left = left * containerWidth,
            top = top * containerHeight,
            right = right * containerWidth,
            bottom = bottom * containerHeight
        )
    }

    companion object {
        fun fromRect(rect: Rect, containerWidth: Float, containerHeight: Float): CropRect {
            return CropRect(
                left = (rect.left / containerWidth).coerceIn(0f, 1f),
                top = (rect.top / containerHeight).coerceIn(0f, 1f),
                right = (rect.right / containerWidth).coerceIn(0f, 1f),
                bottom = (rect.bottom / containerHeight).coerceIn(0f, 1f)
            )
        }
    }
}

/**
 * Enum representing which part of the crop rectangle is being dragged
 */
private enum class DragHandle {
    NONE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    CENTER // For moving the entire rectangle
}

/**
 * A draggable crop rectangle overlay for defining the capture region.
 *
 * @param cropRect The current crop rectangle bounds (as fractions 0-1)
 * @param onCropRectChanged Callback when the crop rectangle is changed by user interaction
 * @param enabled Whether the crop rectangle is interactive
 * @param modifier Modifier for the composable
 */
@Composable
fun CropRectangleOverlay(
    cropRect: CropRect,
    onCropRectChanged: (CropRect) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleRadius = with(density) { 12.dp.toPx() }
    val touchSlop = with(density) { 24.dp.toPx() }
    val minSize = with(density) { 50.dp.toPx() }

    var containerSize by remember { mutableStateOf(Size.Zero) }
    var currentDragHandle by remember { mutableStateOf(DragHandle.NONE) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enabled, cropRect) {
                if (!enabled) return@pointerInput

                detectDragGestures(
                    onDragStart = { offset ->
                        containerSize = Size(size.width.toFloat(), size.height.toFloat())
                        val rect = cropRect.toRect(containerSize.width, containerSize.height)
                        currentDragHandle = detectDragHandle(offset, rect, touchSlop)
                    },
                    onDragEnd = {
                        currentDragHandle = DragHandle.NONE
                    },
                    onDragCancel = {
                        currentDragHandle = DragHandle.NONE
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()

                        if (currentDragHandle == DragHandle.NONE) return@detectDragGestures

                        val rect = cropRect.toRect(containerSize.width, containerSize.height)
                        val newRect = applyDrag(
                            rect = rect,
                            handle = currentDragHandle,
                            dragAmount = dragAmount,
                            containerSize = containerSize,
                            minSize = minSize
                        )

                        onCropRectChanged(
                            CropRect.fromRect(newRect, containerSize.width, containerSize.height)
                        )
                    }
                )
            }
    ) {
        containerSize = size
        val rect = cropRect.toRect(size.width, size.height)

        // Draw dimmed area outside the crop rectangle
        drawDimmedArea(rect)

        // Draw the crop rectangle border
        drawCropBorder(rect)

        // Draw corner and edge handles
        if (enabled) {
            drawHandles(rect, handleRadius)
        }

        // Draw grid lines inside crop area (rule of thirds)
        drawGridLines(rect)
    }
}

/**
 * Detects which drag handle (if any) is under the given touch point
 */
private fun detectDragHandle(
    touchPoint: Offset,
    rect: Rect,
    touchSlop: Float
): DragHandle {
    val corners = listOf(
        rect.topLeft to DragHandle.TOP_LEFT,
        rect.topRight to DragHandle.TOP_RIGHT,
        rect.bottomLeft to DragHandle.BOTTOM_LEFT,
        rect.bottomRight to DragHandle.BOTTOM_RIGHT
    )

    // Check corners first (higher priority)
    for ((corner, handle) in corners) {
        if ((touchPoint - corner).getDistance() < touchSlop) {
            return handle
        }
    }

    // Check edges
    val edgeTouchSlop = touchSlop * 0.8f

    // Left edge
    if (abs(touchPoint.x - rect.left) < edgeTouchSlop &&
        touchPoint.y in rect.top..rect.bottom
    ) {
        return DragHandle.LEFT
    }

    // Right edge
    if (abs(touchPoint.x - rect.right) < edgeTouchSlop &&
        touchPoint.y in rect.top..rect.bottom
    ) {
        return DragHandle.RIGHT
    }

    // Top edge
    if (abs(touchPoint.y - rect.top) < edgeTouchSlop &&
        touchPoint.x in rect.left..rect.right
    ) {
        return DragHandle.TOP
    }

    // Bottom edge
    if (abs(touchPoint.y - rect.bottom) < edgeTouchSlop &&
        touchPoint.x in rect.left..rect.right
    ) {
        return DragHandle.BOTTOM
    }

    // Check if inside rectangle (for moving)
    if (rect.contains(touchPoint)) {
        return DragHandle.CENTER
    }

    return DragHandle.NONE
}

/**
 * Applies drag movement to the rectangle based on which handle is being dragged
 */
private fun applyDrag(
    rect: Rect,
    handle: DragHandle,
    dragAmount: Offset,
    containerSize: Size,
    minSize: Float
): Rect {
    var newLeft = rect.left
    var newTop = rect.top
    var newRight = rect.right
    var newBottom = rect.bottom

    when (handle) {
        DragHandle.TOP_LEFT -> {
            newLeft = (rect.left + dragAmount.x).coerceIn(0f, rect.right - minSize)
            newTop = (rect.top + dragAmount.y).coerceIn(0f, rect.bottom - minSize)
        }
        DragHandle.TOP_RIGHT -> {
            newRight = (rect.right + dragAmount.x).coerceIn(rect.left + minSize, containerSize.width)
            newTop = (rect.top + dragAmount.y).coerceIn(0f, rect.bottom - minSize)
        }
        DragHandle.BOTTOM_LEFT -> {
            newLeft = (rect.left + dragAmount.x).coerceIn(0f, rect.right - minSize)
            newBottom = (rect.bottom + dragAmount.y).coerceIn(rect.top + minSize, containerSize.height)
        }
        DragHandle.BOTTOM_RIGHT -> {
            newRight = (rect.right + dragAmount.x).coerceIn(rect.left + minSize, containerSize.width)
            newBottom = (rect.bottom + dragAmount.y).coerceIn(rect.top + minSize, containerSize.height)
        }
        DragHandle.LEFT -> {
            newLeft = (rect.left + dragAmount.x).coerceIn(0f, rect.right - minSize)
        }
        DragHandle.RIGHT -> {
            newRight = (rect.right + dragAmount.x).coerceIn(rect.left + minSize, containerSize.width)
        }
        DragHandle.TOP -> {
            newTop = (rect.top + dragAmount.y).coerceIn(0f, rect.bottom - minSize)
        }
        DragHandle.BOTTOM -> {
            newBottom = (rect.bottom + dragAmount.y).coerceIn(rect.top + minSize, containerSize.height)
        }
        DragHandle.CENTER -> {
            val width = rect.width
            val height = rect.height
            newLeft = (rect.left + dragAmount.x).coerceIn(0f, containerSize.width - width)
            newTop = (rect.top + dragAmount.y).coerceIn(0f, containerSize.height - height)
            newRight = newLeft + width
            newBottom = newTop + height
        }
        DragHandle.NONE -> { /* No change */ }
    }

    return Rect(newLeft, newTop, newRight, newBottom)
}

/**
 * Draws the dimmed area outside the crop rectangle
 */
private fun DrawScope.drawDimmedArea(rect: Rect) {
    val dimColor = Color.Black.copy(alpha = 0.6f)

    // Top area
    drawRect(
        color = dimColor,
        topLeft = Offset.Zero,
        size = Size(size.width, rect.top)
    )

    // Bottom area
    drawRect(
        color = dimColor,
        topLeft = Offset(0f, rect.bottom),
        size = Size(size.width, size.height - rect.bottom)
    )

    // Left area (between top and bottom)
    drawRect(
        color = dimColor,
        topLeft = Offset(0f, rect.top),
        size = Size(rect.left, rect.height)
    )

    // Right area (between top and bottom)
    drawRect(
        color = dimColor,
        topLeft = Offset(rect.right, rect.top),
        size = Size(size.width - rect.right, rect.height)
    )
}

/**
 * Draws the crop rectangle border
 */
private fun DrawScope.drawCropBorder(rect: Rect) {
    val borderColor = Color.White
    val borderWidth = 2.dp.toPx()

    drawRect(
        color = borderColor,
        topLeft = rect.topLeft,
        size = Size(rect.width, rect.height),
        style = Stroke(width = borderWidth)
    )
}

/**
 * Draws the corner and edge handles
 */
private fun DrawScope.drawHandles(rect: Rect, handleRadius: Float) {
    val handleColor = Color.White
    val handleStroke = 3.dp.toPx()
    val cornerLength = 20.dp.toPx()

    // Draw corner L-shaped handles
    val corners = listOf(
        Triple(rect.topLeft, Offset(1f, 0f), Offset(0f, 1f)),
        Triple(rect.topRight, Offset(-1f, 0f), Offset(0f, 1f)),
        Triple(rect.bottomLeft, Offset(1f, 0f), Offset(0f, -1f)),
        Triple(rect.bottomRight, Offset(-1f, 0f), Offset(0f, -1f))
    )

    for ((corner, hDir, vDir) in corners) {
        // Horizontal line
        drawLine(
            color = handleColor,
            start = corner,
            end = Offset(corner.x + hDir.x * cornerLength, corner.y),
            strokeWidth = handleStroke
        )
        // Vertical line
        drawLine(
            color = handleColor,
            start = corner,
            end = Offset(corner.x, corner.y + vDir.y * cornerLength),
            strokeWidth = handleStroke
        )
    }

    // Draw small circles at corners for touch feedback
    for ((corner, _, _) in corners) {
        drawCircle(
            color = handleColor,
            radius = 6.dp.toPx(),
            center = corner
        )
    }
}

/**
 * Draws grid lines (rule of thirds) inside the crop area
 */
private fun DrawScope.drawGridLines(rect: Rect) {
    val gridColor = Color.White.copy(alpha = 0.3f)
    val gridStroke = 1.dp.toPx()

    // Vertical lines (divide into thirds)
    val thirdWidth = rect.width / 3
    for (i in 1..2) {
        val x = rect.left + thirdWidth * i
        drawLine(
            color = gridColor,
            start = Offset(x, rect.top),
            end = Offset(x, rect.bottom),
            strokeWidth = gridStroke
        )
    }

    // Horizontal lines (divide into thirds)
    val thirdHeight = rect.height / 3
    for (i in 1..2) {
        val y = rect.top + thirdHeight * i
        drawLine(
            color = gridColor,
            start = Offset(rect.left, y),
            end = Offset(rect.right, y),
            strokeWidth = gridStroke
        )
    }
}
