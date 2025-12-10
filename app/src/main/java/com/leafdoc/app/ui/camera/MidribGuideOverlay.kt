package com.leafdoc.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A horizontal guide line overlay to help users align the corn leaf midrib.
 *
 * The line can be dragged up/down to adjust its position, and the thickness
 * can be adjusted by dragging the edge handles. Can be locked to prevent
 * accidental movement during capture.
 *
 * @param guidePositionPercent Vertical position as percentage of height (0.0 = top, 1.0 = bottom)
 * @param guideThicknessPercent Thickness as percentage of height (0.01 = 1%, 0.1 = 10%)
 * @param onPositionChanged Callback when the guide position changes
 * @param onThicknessChanged Callback when the guide thickness changes
 * @param isLocked When true, the guide cannot be moved or resized
 * @param isVisible When false, the guide is not shown
 * @param modifier Modifier for the composable
 */
@Composable
fun MidribGuideOverlay(
    guidePositionPercent: Float,
    guideThicknessPercent: Float = 0.05f, // Default 5% of screen height
    onPositionChanged: (Float) -> Unit,
    onThicknessChanged: (Float) -> Unit = {},
    isLocked: Boolean = false,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    val density = LocalDensity.current
    val handleRadius = with(density) { 14.dp.toPx() }
    val edgeHandleTouchSlop = with(density) { 25.dp.toPx() }
    val minThicknessPercent = 0.02f // Minimum 2% of height
    val maxThicknessPercent = 0.15f // Maximum 15% of height

    // Local state for smooth dragging
    var localPosition by remember { mutableStateOf(guidePositionPercent) }
    var localThickness by remember { mutableStateOf(guideThicknessPercent) }
    var containerHeight by remember { mutableStateOf(0f) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }

    // Sync local state when external values change (but not during drag)
    LaunchedEffect(guidePositionPercent) {
        if (dragMode == DragMode.NONE) {
            localPosition = guidePositionPercent
        }
    }
    LaunchedEffect(guideThicknessPercent) {
        if (dragMode == DragMode.NONE) {
            localThickness = guideThicknessPercent
        }
    }

    // Colors - light green gradient, semi-transparent
    val guideColor = if (isLocked) {
        Color(0xCCFFD700) // Yellow when locked (0xCC = 80% opacity)
    } else {
        Color(0x9990EE90) // Light green semi-transparent (0x99 = 60% opacity)
    }

    val handleColor = if (isLocked) {
        Color(0xDDFFD700)
    } else {
        Color(0xDD90EE90)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isLocked) {
                if (isLocked) return@pointerInput

                detectDragGestures(
                    onDragStart = { offset ->
                        containerHeight = size.height.toFloat()
                        val lineY = localPosition * containerHeight
                        val halfThickness = (localThickness * containerHeight) / 2
                        val topEdge = lineY - halfThickness
                        val bottomEdge = lineY + halfThickness

                        // Determine drag mode based on touch position
                        dragMode = when {
                            abs(offset.y - topEdge) < edgeHandleTouchSlop -> DragMode.TOP_EDGE
                            abs(offset.y - bottomEdge) < edgeHandleTouchSlop -> DragMode.BOTTOM_EDGE
                            offset.y in (topEdge - edgeHandleTouchSlop)..(bottomEdge + edgeHandleTouchSlop) -> DragMode.MOVE
                            else -> DragMode.NONE
                        }
                    },
                    onDragEnd = {
                        // Commit final values
                        onPositionChanged(localPosition)
                        onThicknessChanged(localThickness)
                        dragMode = DragMode.NONE
                    },
                    onDragCancel = {
                        // Revert to external values
                        localPosition = guidePositionPercent
                        localThickness = guideThicknessPercent
                        dragMode = DragMode.NONE
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (dragMode == DragMode.NONE) return@detectDragGestures

                        val lineY = localPosition * containerHeight
                        val halfThickness = (localThickness * containerHeight) / 2

                        when (dragMode) {
                            DragMode.MOVE -> {
                                // Move the entire guide
                                val newY = (lineY + dragAmount.y).coerceIn(0f, containerHeight)
                                localPosition = (newY / containerHeight).coerceIn(0f, 1f)
                            }
                            DragMode.TOP_EDGE -> {
                                // Adjust thickness by moving top edge
                                val topEdge = lineY - halfThickness
                                val newTopEdge = (topEdge + dragAmount.y).coerceAtLeast(0f)
                                val bottomEdge = lineY + halfThickness
                                val newThickness = (bottomEdge - newTopEdge) / containerHeight

                                if (newThickness in minThicknessPercent..maxThicknessPercent) {
                                    localThickness = newThickness
                                    // Adjust position to keep bottom edge fixed
                                    localPosition = ((newTopEdge + bottomEdge) / 2) / containerHeight
                                }
                            }
                            DragMode.BOTTOM_EDGE -> {
                                // Adjust thickness by moving bottom edge
                                val topEdge = lineY - halfThickness
                                val bottomEdge = lineY + halfThickness
                                val newBottomEdge = (bottomEdge + dragAmount.y).coerceAtMost(containerHeight)
                                val newThickness = (newBottomEdge - topEdge) / containerHeight

                                if (newThickness in minThicknessPercent..maxThicknessPercent) {
                                    localThickness = newThickness
                                    // Adjust position to keep top edge fixed
                                    localPosition = ((topEdge + newBottomEdge) / 2) / containerHeight
                                }
                            }
                            DragMode.NONE -> { /* No action */ }
                        }
                    }
                )
            }
    ) {
        containerHeight = size.height
        val lineY = size.height * localPosition
        val halfThickness = (localThickness * size.height) / 2
        val topEdge = lineY - halfThickness
        val bottomEdge = lineY + halfThickness

        // Draw the guide band with gradient
        val gradientBrush = Brush.verticalGradient(
            colors = listOf(
                guideColor.copy(alpha = 0.3f),
                guideColor.copy(alpha = 0.6f),
                guideColor.copy(alpha = 0.6f),
                guideColor.copy(alpha = 0.3f)
            ),
            startY = topEdge,
            endY = bottomEdge
        )

        // Draw filled gradient band
        drawRect(
            brush = gradientBrush,
            topLeft = Offset(0f, topEdge),
            size = androidx.compose.ui.geometry.Size(size.width, bottomEdge - topEdge)
        )

        // Draw center line (the actual guide line)
        val centerLineThickness = with(density) { 4.dp.toPx() }
        drawLine(
            color = guideColor.copy(alpha = 0.9f),
            start = Offset(0f, lineY),
            end = Offset(size.width, lineY),
            strokeWidth = centerLineThickness,
            cap = StrokeCap.Round
        )

        // Draw top and bottom edge lines
        val edgeLineThickness = with(density) { 2.dp.toPx() }

        drawLine(
            color = guideColor.copy(alpha = 0.7f),
            start = Offset(0f, topEdge),
            end = Offset(size.width, topEdge),
            strokeWidth = edgeLineThickness
        )

        drawLine(
            color = guideColor.copy(alpha = 0.7f),
            start = Offset(0f, bottomEdge),
            end = Offset(size.width, bottomEdge),
            strokeWidth = edgeLineThickness
        )

        // Draw handles when not locked
        if (!isLocked) {
            // Center handle (for moving)
            drawCircle(
                color = handleColor,
                radius = handleRadius,
                center = Offset(size.width / 2, lineY)
            )

            // Draw move arrows icon in center handle
            val arrowSize = with(density) { 5.dp.toPx() }
            val arrowColor = Color.White.copy(alpha = 0.8f)

            // Up arrow
            drawLine(
                color = arrowColor,
                start = Offset(size.width / 2, lineY - arrowSize * 1.5f),
                end = Offset(size.width / 2 - arrowSize, lineY - arrowSize * 0.5f),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = arrowColor,
                start = Offset(size.width / 2, lineY - arrowSize * 1.5f),
                end = Offset(size.width / 2 + arrowSize, lineY - arrowSize * 0.5f),
                strokeWidth = 2.dp.toPx()
            )

            // Down arrow
            drawLine(
                color = arrowColor,
                start = Offset(size.width / 2, lineY + arrowSize * 1.5f),
                end = Offset(size.width / 2 - arrowSize, lineY + arrowSize * 0.5f),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = arrowColor,
                start = Offset(size.width / 2, lineY + arrowSize * 1.5f),
                end = Offset(size.width / 2 + arrowSize, lineY + arrowSize * 0.5f),
                strokeWidth = 2.dp.toPx()
            )

            // Top edge handles (for resizing)
            val edgeHandleRadius = handleRadius * 0.7f
            val handleSpacing = size.width / 4

            for (i in 1..3) {
                val handleX = handleSpacing * i

                // Top edge handle
                drawCircle(
                    color = handleColor.copy(alpha = 0.8f),
                    radius = edgeHandleRadius,
                    center = Offset(handleX, topEdge)
                )

                // Bottom edge handle
                drawCircle(
                    color = handleColor.copy(alpha = 0.8f),
                    radius = edgeHandleRadius,
                    center = Offset(handleX, bottomEdge)
                )
            }

            // Draw resize arrows on edge handles
            val resizeArrowSize = with(density) { 3.dp.toPx() }

            // Top edge - down arrows
            drawLine(
                color = arrowColor,
                start = Offset(size.width / 2, topEdge - resizeArrowSize),
                end = Offset(size.width / 2, topEdge + resizeArrowSize),
                strokeWidth = 2.dp.toPx()
            )

            // Bottom edge - up arrows
            drawLine(
                color = arrowColor,
                start = Offset(size.width / 2, bottomEdge - resizeArrowSize),
                end = Offset(size.width / 2, bottomEdge + resizeArrowSize),
                strokeWidth = 2.dp.toPx()
            )
        } else {
            // When locked, show lock indicator at center
            val lockIndicatorRadius = handleRadius * 0.5f
            drawCircle(
                color = handleColor,
                radius = lockIndicatorRadius,
                center = Offset(size.width / 2, lineY)
            )
        }

        // Draw edge markers (vertical lines at screen edges)
        val markerWidth = with(density) { 4.dp.toPx() }

        // Left edge marker
        drawLine(
            color = guideColor,
            start = Offset(0f, topEdge),
            end = Offset(0f, bottomEdge),
            strokeWidth = markerWidth
        )

        // Right edge marker
        drawLine(
            color = guideColor,
            start = Offset(size.width, topEdge),
            end = Offset(size.width, bottomEdge),
            strokeWidth = markerWidth
        )
    }
}

/**
 * Drag mode for the midrib guide
 */
private enum class DragMode {
    NONE,
    MOVE,       // Moving the entire guide up/down
    TOP_EDGE,   // Resizing by dragging top edge
    BOTTOM_EDGE // Resizing by dragging bottom edge
}
