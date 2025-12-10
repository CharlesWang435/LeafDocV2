package com.leafdoc.app.ui.camera

import android.Manifest
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.leafdoc.app.camera.CameraCapabilities
import com.leafdoc.app.camera.CameraState
import com.leafdoc.app.camera.ProCameraController
import com.leafdoc.app.data.model.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToResults: (String) -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsState()
    val cameraSettings by viewModel.cameraSettings.collectAsState()
    val capturedSegments by viewModel.capturedSegments.collectAsState()
    val overlapPercentage by viewModel.overlapPercentage.collectAsState()

    var cameraController by remember { mutableStateOf<ProCameraController?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var showControls by remember { mutableStateOf(false) }
    var showSessionDialog by remember { mutableStateOf(false) }
    var showProControls by remember { mutableStateOf(false) }

    val cameraCapabilities by cameraController?.cameraCapabilities?.collectAsState() ?: remember { mutableStateOf(null) }
    val cameraState by cameraController?.cameraState?.collectAsState() ?: remember { mutableStateOf(CameraState.Idle) }
    val histogramData by cameraController?.histogramData?.collectAsState() ?: remember { mutableStateOf(null) }

    val permissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    LaunchedEffect(uiState.sessionComplete) {
        if (uiState.sessionComplete && uiState.stitchedImagePath != null) {
            uiState.sessionId?.let { sessionId ->
                onNavigateToResults(sessionId)
            }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            // Show error snackbar
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (permissions.allPermissionsGranted) {
            // Camera Preview
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = this

                        // Initialize camera
                        val controller = ProCameraController(ctx)
                        cameraController = controller

                        scope.launch {
                            controller.initialize(lifecycleOwner, this@apply, cameraSettings)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                previewView?.let { view ->
                                    cameraController?.focusOnPoint(offset.x, offset.y, view)
                                }
                            },
                            onDoubleTap = {
                                showProControls = !showProControls
                            }
                        )
                    }
            )

            // Grid Overlay
            if (cameraSettings.gridOverlay != GridOverlayType.NONE) {
                GridOverlay(
                    gridType = cameraSettings.gridOverlay,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Overlap Guide (show previous segment edge)
            if (uiState.sessionActive && capturedSegments.isNotEmpty()) {
                OverlapGuideOverlay(
                    overlapPercentage = overlapPercentage,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top Bar
            TopBar(
                sessionActive = uiState.sessionActive,
                segmentCount = uiState.segmentCount,
                onSettingsClick = onNavigateToSettings,
                onGalleryClick = onNavigateToGallery,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp)
            )

            // Histogram
            if (cameraSettings.showHistogram && histogramData != null) {
                HistogramView(
                    data = histogramData!!,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 72.dp, end = 16.dp)
                        .size(120.dp, 60.dp)
                )
            }

            // Pro Controls Panel
            AnimatedVisibility(
                visible = showProControls,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ProControlsPanel(
                    settings = cameraSettings,
                    capabilities = cameraCapabilities,
                    onIsoChange = { viewModel.updateIso(it) },
                    onShutterChange = { viewModel.updateShutterSpeed(it) },
                    onFocusChange = { viewModel.updateFocusDistance(it) },
                    onWhiteBalanceChange = { viewModel.updateWhiteBalance(it) },
                    onExposureCompensationChange = { viewModel.updateExposureCompensation(it) },
                    onFlashChange = { viewModel.updateFlashMode(it) },
                    onClose = { showProControls = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 200.dp)
                )
            }

            // Bottom Controls
            BottomControls(
                sessionActive = uiState.sessionActive,
                segmentCount = uiState.segmentCount,
                isProcessing = uiState.isProcessing,
                isStitching = uiState.isStitching,
                cameraState = cameraState,
                lastThumbnail = uiState.lastCapturedThumbnail,
                onStartSession = { showSessionDialog = true },
                onCapture = {
                    scope.launch {
                        cameraController?.captureImage()?.let { image ->
                            viewModel.onImageCaptured(image)
                        }
                    }
                },
                onFinishSession = { viewModel.finishSession() },
                onCancelSession = { viewModel.cancelSession() },
                onDeleteLast = { viewModel.deleteLastSegment() },
                onToggleControls = { showProControls = !showProControls },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            )

            // Captured Segments Strip
            if (capturedSegments.isNotEmpty()) {
                SegmentsStrip(
                    segments = capturedSegments,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, bottom = 140.dp)
                )
            }

            // Status Message
            uiState.message?.let { message ->
                StatusMessage(
                    message = message,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 72.dp)
                )
            }

            // Processing Overlay
            if (uiState.isStitching) {
                ProcessingOverlay(
                    message = "Creating panorama...",
                    modifier = Modifier.fillMaxSize()
                )
            }

        } else {
            // Permission Request
            PermissionRequest(
                onRequestPermissions = { permissions.launchMultiplePermissionRequest() }
            )
        }
    }

    // Session Start Dialog
    if (showSessionDialog) {
        SessionStartDialog(
            onDismiss = { showSessionDialog = false },
            onConfirm = { farmerId, fieldId, leafNumber ->
                viewModel.startNewSession(farmerId, fieldId, leafNumber)
                showSessionDialog = false
            }
        )
    }

    // Apply camera settings when they change
    LaunchedEffect(cameraSettings) {
        cameraController?.applySettings(cameraSettings)
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraController?.release()
        }
    }
}

@Composable
private fun TopBar(
    sessionActive: Boolean,
    segmentCount: Int,
    onSettingsClick: () -> Unit,
    onGalleryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onGalleryClick,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Gallery",
                tint = Color.White
            )
        }

        if (sessionActive) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Camera,
                        contentDescription = null,
                        tint = Color.Green,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$segmentCount segments",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun BottomControls(
    sessionActive: Boolean,
    segmentCount: Int,
    isProcessing: Boolean,
    isStitching: Boolean,
    cameraState: CameraState,
    lastThumbnail: String?,
    onStartSession: () -> Unit,
    onCapture: () -> Unit,
    onFinishSession: () -> Unit,
    onCancelSession: () -> Unit,
    onDeleteLast: () -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Secondary controls row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel / Delete last
            if (sessionActive) {
                IconButton(
                    onClick = if (segmentCount > 0) onDeleteLast else onCancelSession,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (segmentCount > 0) Icons.Default.Undo else Icons.Default.Close,
                        contentDescription = if (segmentCount > 0) "Delete last" else "Cancel",
                        tint = Color.White
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }

            // Capture button
            CaptureButton(
                enabled = !isProcessing && cameraState == CameraState.Ready,
                sessionActive = sessionActive,
                isCapturing = cameraState == CameraState.Capturing,
                onCapture = if (sessionActive) onCapture else onStartSession,
                modifier = Modifier.size(80.dp)
            )

            // Finish / Pro controls
            if (sessionActive && segmentCount > 0) {
                IconButton(
                    onClick = onFinishSession,
                    enabled = !isProcessing,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Green.copy(alpha = 0.8f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Finish",
                        tint = Color.White
                    )
                }
            } else {
                IconButton(
                    onClick = onToggleControls,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Pro Controls",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureButton(
    enabled: Boolean,
    sessionActive: Boolean,
    isCapturing: Boolean,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.9f))
            .border(4.dp, if (sessionActive) Color.Green else Color.White, CircleShape)
            .clickable(enabled = enabled, onClick = onCapture),
        contentAlignment = Alignment.Center
    ) {
        if (isCapturing) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = Color.Green,
                strokeWidth = 3.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (sessionActive) Color.Green else Color.Red)
            )
        }
    }
}

@Composable
private fun GridOverlay(
    gridType: GridOverlayType,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.5f)

        when (gridType) {
            GridOverlayType.THIRDS -> {
                // Vertical lines
                drawLine(color, Offset(size.width / 3, 0f), Offset(size.width / 3, size.height), strokeWidth)
                drawLine(color, Offset(2 * size.width / 3, 0f), Offset(2 * size.width / 3, size.height), strokeWidth)
                // Horizontal lines
                drawLine(color, Offset(0f, size.height / 3), Offset(size.width, size.height / 3), strokeWidth)
                drawLine(color, Offset(0f, 2 * size.height / 3), Offset(size.width, 2 * size.height / 3), strokeWidth)
            }
            GridOverlayType.CENTER -> {
                // Center cross
                drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth)
                drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth)
            }
            GridOverlayType.GRID_4X4 -> {
                for (i in 1..3) {
                    drawLine(color, Offset(i * size.width / 4, 0f), Offset(i * size.width / 4, size.height), strokeWidth)
                    drawLine(color, Offset(0f, i * size.height / 4), Offset(size.width, i * size.height / 4), strokeWidth)
                }
            }
            GridOverlayType.GOLDEN -> {
                val phi = 1.618f
                val x1 = size.width / (1 + phi)
                val x2 = size.width - x1
                val y1 = size.height / (1 + phi)
                val y2 = size.height - y1
                drawLine(color, Offset(x1, 0f), Offset(x1, size.height), strokeWidth)
                drawLine(color, Offset(x2, 0f), Offset(x2, size.height), strokeWidth)
                drawLine(color, Offset(0f, y1), Offset(size.width, y1), strokeWidth)
                drawLine(color, Offset(0f, y2), Offset(size.width, y2), strokeWidth)
            }
            GridOverlayType.NONE -> {}
        }
    }
}

@Composable
private fun OverlapGuideOverlay(
    overlapPercentage: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val overlapWidth = size.width * overlapPercentage / 100

        // Semi-transparent overlay for overlap region
        drawRect(
            color = Color.Green.copy(alpha = 0.2f),
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(overlapWidth, size.height)
        )

        // Border line
        drawLine(
            color = Color.Green,
            start = Offset(overlapWidth, 0f),
            end = Offset(overlapWidth, size.height),
            strokeWidth = 3.dp.toPx()
        )

        // Arrow indicators
        val arrowSize = 20.dp.toPx()
        val arrowY = size.height / 2

        val path = Path().apply {
            moveTo(overlapWidth + arrowSize, arrowY - arrowSize / 2)
            lineTo(overlapWidth + 5.dp.toPx(), arrowY)
            lineTo(overlapWidth + arrowSize, arrowY + arrowSize / 2)
        }
        drawPath(path, Color.Green, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun HistogramView(
    data: IntArray,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val maxValue = data.maxOrNull()?.toFloat() ?: 1f
            val barWidth = size.width / 256

            for (i in data.indices) {
                val barHeight = (data[i] / maxValue) * size.height
                drawLine(
                    color = Color.White.copy(alpha = 0.8f),
                    start = Offset(i * barWidth, size.height),
                    end = Offset(i * barWidth, size.height - barHeight),
                    strokeWidth = barWidth
                )
            }
        }
    }
}

@Composable
private fun SegmentsStrip(
    segments: List<CapturedSegmentInfo>,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(segments) { segment ->
            Surface(
                modifier = Modifier.size(60.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.5f)
            ) {
                AsyncImage(
                    model = segment.thumbnailPath ?: segment.imagePath,
                    contentDescription = "Segment ${segment.index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Text(
                        text = "${segment.index + 1}",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusMessage(
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ProcessingOverlay(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = Color.Green,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PermissionRequest(
    onRequestPermissions: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Camera Permission Required",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "LeafDoc needs camera and location access to capture and tag leaf images.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionStartDialog(
    onDismiss: () -> Unit,
    onConfirm: (farmerId: String, fieldId: String, leafNumber: Int) -> Unit
) {
    var farmerId by remember { mutableStateOf("") }
    var fieldId by remember { mutableStateOf("") }
    var leafNumber by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start New Session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = farmerId,
                    onValueChange = { farmerId = it },
                    label = { Text("Farmer ID (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fieldId,
                    onValueChange = { fieldId = it },
                    label = { Text("Field ID (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = leafNumber,
                    onValueChange = { leafNumber = it.filter { c -> c.isDigit() } },
                    label = { Text("Leaf Number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        farmerId,
                        fieldId,
                        leafNumber.toIntOrNull() ?: 1
                    )
                }
            ) {
                Text("Start Capture")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ProControlsPanel(
    settings: CameraSettings,
    capabilities: CameraCapabilities?,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Long) -> Unit,
    onFocusChange: (Float) -> Unit,
    onWhiteBalanceChange: (WhiteBalanceMode) -> Unit,
    onExposureCompensationChange: (Float) -> Unit,
    onFlashChange: (FlashMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.85f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pro Controls",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ISO Control
            ControlRow(
                label = "ISO",
                value = CameraSettings.isoToString(settings.iso),
                options = CameraSettings.ISO_VALUES.map { CameraSettings.isoToString(it) },
                onSelect = { index ->
                    onIsoChange(CameraSettings.ISO_VALUES[index])
                }
            )

            // Shutter Speed Control
            ControlRow(
                label = "Shutter",
                value = CameraSettings.shutterSpeedToString(settings.shutterSpeed),
                options = CameraSettings.SHUTTER_SPEEDS.map { CameraSettings.shutterSpeedToString(it) },
                onSelect = { index ->
                    onShutterChange(CameraSettings.SHUTTER_SPEEDS[index])
                }
            )

            // White Balance Control
            ControlRow(
                label = "WB",
                value = settings.whiteBalance.displayName,
                options = WhiteBalanceMode.entries.map { it.displayName },
                onSelect = { index ->
                    onWhiteBalanceChange(WhiteBalanceMode.entries[index])
                }
            )

            // Flash Control
            ControlRow(
                label = "Flash",
                value = settings.flashMode.displayName,
                options = FlashMode.entries.map { it.displayName },
                onSelect = { index ->
                    onFlashChange(FlashMode.entries[index])
                }
            )

            // Exposure Compensation Slider
            if (capabilities != null && settings.iso == CameraSettings.ISO_AUTO) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Exposure: ${if (settings.exposureCompensation >= 0) "+" else ""}${"%.1f".format(settings.exposureCompensation)} EV",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Slider(
                    value = settings.exposureCompensation,
                    onValueChange = onExposureCompensationChange,
                    valueRange = (capabilities.exposureCompensationRange.lower * capabilities.exposureCompensationStep)..(capabilities.exposureCompensationRange.upper * capabilities.exposureCompensationStep),
                    steps = capabilities.exposureCompensationRange.upper - capabilities.exposureCompensationRange.lower - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.Green
                    )
                )
            }

            // Focus Distance Slider (Manual Focus)
            if (capabilities != null && capabilities.minFocusDistance > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Focus: ${if (settings.focusDistance == CameraSettings.FOCUS_AUTO) "Auto" else "Manual"}",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    TextButton(
                        onClick = {
                            onFocusChange(
                                if (settings.focusDistance == CameraSettings.FOCUS_AUTO)
                                    capabilities.hyperfocalDistance
                                else
                                    CameraSettings.FOCUS_AUTO
                            )
                        }
                    ) {
                        Text(
                            text = if (settings.focusDistance == CameraSettings.FOCUS_AUTO) "Manual" else "Auto",
                            color = Color.Green
                        )
                    }
                }
                if (settings.focusDistance != CameraSettings.FOCUS_AUTO) {
                    Slider(
                        value = settings.focusDistance,
                        onValueChange = onFocusChange,
                        valueRange = 0f..capabilities.minFocusDistance,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.Green
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlRow(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.width(60.dp)
        )

        Box {
            Surface(
                modifier = Modifier.clickable { expanded = true },
                color = Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = value,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
