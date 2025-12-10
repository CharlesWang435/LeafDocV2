package com.leafdoc.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/**
 * Fullscreen zoomable and pannable image viewer dialog.
 * Supports pinch-to-zoom and drag-to-pan gestures.
 *
 * @param imagePath The path to the image to display
 * @param onDismiss Callback when the user closes the viewer
 */
@Composable
fun ZoomableImageDialog(
    imagePath: String?,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (imagePath != null) {
                ZoomableImageContent(
                    imagePath = imagePath,
                    onDismiss = onDismiss
                )
            }

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * The core zoomable and pannable image content.
 * Handles all gesture transformations.
 */
@Composable
private fun ZoomableImageContent(
    imagePath: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)

        // Calculate max offset to prevent panning beyond image bounds
        val maxOffsetX = (newScale - 1f) * 500f // Approximate based on container
        val maxOffsetY = (newScale - 1f) * 500f

        scale = newScale
        offset = Offset(
            x = (offset.x + offsetChange.x).coerceIn(-maxOffsetX, maxOffsetX),
            y = (offset.y + offsetChange.y).coerceIn(-maxOffsetY, maxOffsetY)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        // Double tap to toggle zoom between 1x and 2.5x
                        if (scale > 1.5f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            // Center zoom on tap location
                            val centerX = size.width / 2
                            val centerY = size.height / 2
                            offset = Offset(
                                x = (centerX - tapOffset.x) * 0.5f,
                                y = (centerY - tapOffset.y) * 0.5f
                            )
                        }
                    }
                )
            }
            .transformable(state = state),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imagePath,
            contentDescription = "Zoomable image",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * Clickable image that opens in fullscreen zoom viewer when tapped.
 * Use this to wrap any image in your UI that should support zoom.
 *
 * @param imagePath The path to the image
 * @param contentDescription Accessibility description
 * @param modifier Modifier for the image container
 * @param contentScale How to scale the image in its container
 */
@Composable
fun ClickableZoomableImage(
    imagePath: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    var showZoomDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AsyncImage(
            model = imagePath,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (imagePath != null) {
                                showZoomDialog = true
                            }
                        }
                    )
                },
            contentScale = contentScale
        )
    }

    if (showZoomDialog && imagePath != null) {
        ZoomableImageDialog(
            imagePath = imagePath,
            onDismiss = { showZoomDialog = false }
        )
    }
}
