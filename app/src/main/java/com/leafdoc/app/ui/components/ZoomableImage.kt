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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/**
 * Fullscreen zoomable and pannable image viewer dialog.
 * Supports pinch-to-zoom, drag-to-pan, and double-tap-to-zoom gestures.
 *
 * @param imagePath The path to the image to display
 * @param onDismiss Callback when the user closes the viewer
 * @param onViewResults Optional callback to navigate to results screen
 * @param sessionId Optional session ID for the results navigation
 */
@Composable
fun ZoomableImageDialog(
    imagePath: String?,
    onDismiss: () -> Unit,
    onViewResults: ((String) -> Unit)? = null,
    sessionId: String? = null
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

            // Top controls bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Close button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                // Zoom hint
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pinch or double-tap to zoom",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }

                // Placeholder for symmetry
                Spacer(modifier = Modifier.size(48.dp))
            }

            // Bottom button - View Results (if callback provided)
            if (onViewResults != null && sessionId != null) {
                Button(
                    onClick = {
                        onDismiss()
                        onViewResults(sessionId)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 80.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50) // Green
                    ),
                    shape = RoundedCornerShape(24.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "View Results & Diagnosis",
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

/**
 * The core zoomable and pannable image content.
 * Handles all gesture transformations with improved bounds calculation.
 */
@Composable
private fun ZoomableImageContent(
    imagePath: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)

        // Calculate max offset based on actual container size
        val maxOffsetX = (newScale - 1f) * containerSize.width / 2
        val maxOffsetY = (newScale - 1f) * containerSize.height / 2

        scale = newScale
        offset = Offset(
            x = (offset.x + offsetChange.x).coerceIn(-maxOffsetX, maxOffsetX),
            y = (offset.y + offsetChange.y).coerceIn(-maxOffsetY, maxOffsetY)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        // Double tap to toggle zoom between 1x and 3x
                        if (scale > 1.5f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                            // Center zoom on tap location
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            offset = Offset(
                                x = (centerX - tapOffset.x) * (scale - 1) / scale,
                                y = (centerY - tapOffset.y) * (scale - 1) / scale
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
