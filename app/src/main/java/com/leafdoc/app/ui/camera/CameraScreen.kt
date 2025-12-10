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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
    val defaultFarmerId by viewModel.defaultFarmerId.collectAsState()
    val defaultFieldId by viewModel.defaultFieldId.collectAsState()
    val cropRect by viewModel.cropRect.collectAsState()

    // Midrib guide state
    val midribGuideEnabled by viewModel.midribGuideEnabled.collectAsState()
    val midribGuidePosition by viewModel.midribGuidePosition.collectAsState()
    val midribGuideThickness by viewModel.midribGuideThickness.collectAsState()
    val midribGuideLocked by viewModel.midribGuideLocked.collectAsState()
    val cropRectLocked by viewModel.cropRectLocked.collectAsState()
    val midribAlignmentEnabled by viewModel.midribAlignmentEnabled.collectAsState()

    // Pop-out panel state
    var showLockControlsPanel by remember { mutableStateOf(false) }

    var cameraController by remember { mutableStateOf<ProCameraController?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var previewWidth by remember { mutableStateOf(0) }
    var previewHeight by remember { mutableStateOf(0) }
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
                // Reset state after navigation to prevent stale navigation state
                viewModel.resetForNewCapture()
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

                        // Track preview dimensions
                        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                            previewWidth = right - left
                            previewHeight = bottom - top
                            viewModel.updatePreviewDimensions(previewWidth, previewHeight)
                        }

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

            // Crop Rectangle Overlay (always visible)
            CropRectangleOverlay(
                cropRect = cropRect,
                onCropRectChanged = { viewModel.updateCropRect(it) },
                isLocked = cropRectLocked,
                enabled = !uiState.isProcessing,
                modifier = Modifier.fillMaxSize()
            )

            // Midrib Guide Line (horizontal guide for aligning the leaf midrib)
            if (midribGuideEnabled) {
                MidribGuideOverlay(
                    guidePositionPercent = midribGuidePosition,
                    guideThicknessPercent = midribGuideThickness,
                    onPositionChanged = { viewModel.updateMidribGuidePosition(it) },
                    onThicknessChanged = { viewModel.updateMidribGuideThickness(it) },
                    isLocked = midribGuideLocked,
                    isVisible = true,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top Bar with pop-out trigger/panel in center
            TopBar(
                sessionActive = uiState.sessionActive,
                segmentCount = uiState.segmentCount,
                onSettingsClick = onNavigateToSettings,
                onGalleryClick = onNavigateToGallery,
                showLockControlsPanel = showLockControlsPanel,
                onToggleLockControlsPanel = { showLockControlsPanel = !showLockControlsPanel },
                cropRectLocked = cropRectLocked,
                midribGuideLocked = midribGuideLocked,
                midribGuideEnabled = midribGuideEnabled,
                midribAlignmentEnabled = midribAlignmentEnabled,
                onToggleCropLock = { viewModel.toggleCropRectLocked() },
                onToggleMidribLock = { viewModel.toggleMidribGuideLocked() },
                onToggleMidribGuide = { viewModel.toggleMidribGuideEnabled() },
                onToggleMidribAlignment = { viewModel.toggleMidribAlignment() },
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

            // Pro Controls Panel - compact bottom strip design
            AnimatedVisibility(
                visible = showProControls,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 100.dp) // Above the capture button
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
                    onClose = { showProControls = false }
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
            defaultFarmerId = defaultFarmerId,
            defaultFieldId = defaultFieldId,
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
    showLockControlsPanel: Boolean,
    onToggleLockControlsPanel: () -> Unit,
    cropRectLocked: Boolean,
    midribGuideLocked: Boolean,
    midribGuideEnabled: Boolean,
    midribAlignmentEnabled: Boolean,
    onToggleCropLock: () -> Unit,
    onToggleMidribLock: () -> Unit,
    onToggleMidribGuide: () -> Unit,
    onToggleMidribAlignment: () -> Unit,
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

        // Center section: session info, pop-out trigger, or horizontal controls panel
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
        } else if (showLockControlsPanel) {
            // Horizontal controls panel (replaces the arrow when expanded)
            HorizontalLockControlsPanel(
                cropRectLocked = cropRectLocked,
                midribGuideLocked = midribGuideLocked,
                midribGuideEnabled = midribGuideEnabled,
                midribAlignmentEnabled = midribAlignmentEnabled,
                onToggleCropLock = onToggleCropLock,
                onToggleMidribLock = onToggleMidribLock,
                onToggleMidribGuide = onToggleMidribGuide,
                onToggleMidribAlignment = onToggleMidribAlignment,
                onClose = onToggleLockControlsPanel
            )
        } else {
            // Pop-out panel trigger button (center, circular)
            IconButton(
                onClick = onToggleLockControlsPanel,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand panel",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
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
    defaultFarmerId: String,
    defaultFieldId: String,
    onDismiss: () -> Unit,
    onConfirm: (farmerId: String, fieldId: String, leafNumber: Int) -> Unit
) {
    var farmerId by remember { mutableStateOf(defaultFarmerId) }
    var fieldId by remember { mutableStateOf(defaultFieldId) }
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

/**
 * Compact Pro Controls Panel - Horizontal bottom strip design
 * Inspired by professional camera apps (Lightroom, Halide, ProCam)
 */
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
    var selectedControl by remember { mutableStateOf<ProControlType?>(null) }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Expandable detail area (only shows when a control is selected)
        AnimatedVisibility(
            visible = selectedControl != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.9f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    when (selectedControl) {
                        ProControlType.ISO -> {
                            CompactOptionSelector(
                                title = "ISO",
                                options = CameraSettings.ISO_VALUES.map { CameraSettings.isoToString(it) },
                                selectedIndex = CameraSettings.ISO_VALUES.indexOf(settings.iso).coerceAtLeast(0),
                                onSelect = { onIsoChange(CameraSettings.ISO_VALUES[it]) }
                            )
                        }
                        ProControlType.SHUTTER -> {
                            CompactOptionSelector(
                                title = "Shutter Speed",
                                options = CameraSettings.SHUTTER_SPEEDS.map { CameraSettings.shutterSpeedToString(it) },
                                selectedIndex = CameraSettings.SHUTTER_SPEEDS.indexOf(settings.shutterSpeed).coerceAtLeast(0),
                                onSelect = { onShutterChange(CameraSettings.SHUTTER_SPEEDS[it]) }
                            )
                        }
                        ProControlType.WB -> {
                            CompactOptionSelector(
                                title = "White Balance",
                                options = WhiteBalanceMode.entries.map { it.displayName },
                                selectedIndex = WhiteBalanceMode.entries.indexOf(settings.whiteBalance),
                                onSelect = { onWhiteBalanceChange(WhiteBalanceMode.entries[it]) }
                            )
                        }
                        ProControlType.FLASH -> {
                            CompactOptionSelector(
                                title = "Flash",
                                options = FlashMode.entries.map { it.displayName },
                                selectedIndex = FlashMode.entries.indexOf(settings.flashMode),
                                onSelect = { onFlashChange(FlashMode.entries[it]) }
                            )
                        }
                        ProControlType.EV -> {
                            if (capabilities != null) {
                                CompactSliderControl(
                                    title = "Exposure",
                                    value = settings.exposureCompensation,
                                    valueLabel = "${if (settings.exposureCompensation >= 0) "+" else ""}${"%.1f".format(settings.exposureCompensation)} EV",
                                    range = (capabilities.exposureCompensationRange.lower * capabilities.exposureCompensationStep)..(capabilities.exposureCompensationRange.upper * capabilities.exposureCompensationStep),
                                    onValueChange = onExposureCompensationChange
                                )
                            }
                        }
                        ProControlType.FOCUS -> {
                            if (capabilities != null && capabilities.minFocusDistance > 0) {
                                CompactFocusControl(
                                    isAuto = settings.focusDistance == CameraSettings.FOCUS_AUTO,
                                    focusDistance = settings.focusDistance,
                                    maxFocusDistance = capabilities.minFocusDistance,
                                    hyperfocalDistance = capabilities.hyperfocalDistance,
                                    onFocusChange = onFocusChange
                                )
                            }
                        }
                        null -> {}
                    }
                }
            }
        }

        // Main control strip
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.Black.copy(alpha = 0.85f),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Close button
                CompactControlButton(
                    icon = Icons.Default.Close,
                    label = "",
                    value = "",
                    isSelected = false,
                    onClick = onClose,
                    showValue = false
                )

                // ISO
                CompactControlButton(
                    icon = Icons.Default.Iso,
                    label = "ISO",
                    value = CameraSettings.isoToString(settings.iso),
                    isSelected = selectedControl == ProControlType.ISO,
                    onClick = {
                        selectedControl = if (selectedControl == ProControlType.ISO) null else ProControlType.ISO
                    }
                )

                // Shutter
                CompactControlButton(
                    icon = Icons.Default.ShutterSpeed,
                    label = "SS",
                    value = CameraSettings.shutterSpeedToString(settings.shutterSpeed),
                    isSelected = selectedControl == ProControlType.SHUTTER,
                    onClick = {
                        selectedControl = if (selectedControl == ProControlType.SHUTTER) null else ProControlType.SHUTTER
                    }
                )

                // White Balance
                CompactControlButton(
                    icon = Icons.Default.WbSunny,
                    label = "WB",
                    value = settings.whiteBalance.shortName,
                    isSelected = selectedControl == ProControlType.WB,
                    onClick = {
                        selectedControl = if (selectedControl == ProControlType.WB) null else ProControlType.WB
                    }
                )

                // EV (only in auto mode)
                if (settings.iso == CameraSettings.ISO_AUTO && capabilities != null) {
                    CompactControlButton(
                        icon = Icons.Default.Exposure,
                        label = "EV",
                        value = "${if (settings.exposureCompensation >= 0) "+" else ""}${"%.1f".format(settings.exposureCompensation)}",
                        isSelected = selectedControl == ProControlType.EV,
                        onClick = {
                            selectedControl = if (selectedControl == ProControlType.EV) null else ProControlType.EV
                        }
                    )
                }

                // Focus
                if (capabilities != null && capabilities.minFocusDistance > 0) {
                    CompactControlButton(
                        icon = Icons.Default.CenterFocusWeak,
                        label = "AF",
                        value = if (settings.focusDistance == CameraSettings.FOCUS_AUTO) "A" else "M",
                        isSelected = selectedControl == ProControlType.FOCUS,
                        onClick = {
                            selectedControl = if (selectedControl == ProControlType.FOCUS) null else ProControlType.FOCUS
                        }
                    )
                }

                // Flash
                CompactControlButton(
                    icon = when (settings.flashMode) {
                        FlashMode.OFF -> Icons.Default.FlashOff
                        FlashMode.ON -> Icons.Default.FlashOn
                        FlashMode.AUTO -> Icons.Default.FlashAuto
                        FlashMode.TORCH -> Icons.Default.Highlight
                    },
                    label = "⚡",
                    value = settings.flashMode.shortName,
                    isSelected = selectedControl == ProControlType.FLASH,
                    onClick = {
                        selectedControl = if (selectedControl == ProControlType.FLASH) null else ProControlType.FLASH
                    }
                )
            }
        }
    }
}

private enum class ProControlType {
    ISO, SHUTTER, WB, EV, FOCUS, FLASH
}

@Composable
private fun CompactControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    showValue: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) Color.Green else Color.White,
            modifier = Modifier.size(20.dp)
        )
        if (showValue && value.isNotEmpty()) {
            Text(
                text = value,
                color = if (isSelected) Color.Green else Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CompactOptionSelector(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(options.size) { index ->
                val isSelected = index == selectedIndex
                Surface(
                    modifier = Modifier.clickable { onSelect(index) },
                    color = if (isSelected) Color.Green else Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = options[index],
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSliderControl(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
            Text(
                text = valueLabel,
                color = Color.Green,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.Green,
                activeTrackColor = Color.Green,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun CompactFocusControl(
    isAuto: Boolean,
    focusDistance: Float,
    maxFocusDistance: Float,
    hyperfocalDistance: Float,
    onFocusChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Focus",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.clickable { onFocusChange(CameraSettings.FOCUS_AUTO) },
                    color = if (isAuto) Color.Green else Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Auto",
                        color = if (isAuto) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isAuto) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    modifier = Modifier.clickable {
                        if (isAuto) onFocusChange(hyperfocalDistance)
                    },
                    color = if (!isAuto) Color.Green else Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Manual",
                        color = if (!isAuto) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (!isAuto) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        if (!isAuto) {
            Slider(
                value = focusDistance,
                onValueChange = onFocusChange,
                valueRange = 0f..maxFocusDistance,
                modifier = Modifier.height(32.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.Green,
                    activeTrackColor = Color.Green,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }
    }
}

// Extension properties for short display names
private val WhiteBalanceMode.shortName: String
    get() = when (this) {
        WhiteBalanceMode.AUTO -> "A"
        WhiteBalanceMode.DAYLIGHT -> "☀"
        WhiteBalanceMode.CLOUDY -> "☁"
        WhiteBalanceMode.TUNGSTEN -> "💡"
        WhiteBalanceMode.FLUORESCENT -> "F"
        WhiteBalanceMode.FLASH -> "⚡"
        WhiteBalanceMode.SHADE -> "🌳"
        WhiteBalanceMode.CUSTOM -> "C"
    }

private val FlashMode.shortName: String
    get() = when (this) {
        FlashMode.OFF -> "Off"
        FlashMode.ON -> "On"
        FlashMode.AUTO -> "A"
        FlashMode.TORCH -> "T"
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

@Composable
private fun HorizontalLockControlsPanel(
    cropRectLocked: Boolean,
    midribGuideLocked: Boolean,
    midribGuideEnabled: Boolean,
    midribAlignmentEnabled: Boolean,
    onToggleCropLock: () -> Unit,
    onToggleMidribLock: () -> Unit,
    onToggleMidribGuide: () -> Unit,
    onToggleMidribAlignment: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.7f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close button (up arrow)
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Close panel",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(Color.White.copy(alpha = 0.3f))
            )

            // Crop rectangle lock toggle
            HorizontalToggleButton(
                label = "Crop",
                isActive = cropRectLocked,
                activeIcon = Icons.Default.Lock,
                inactiveIcon = Icons.Default.LockOpen,
                activeColor = Color.Yellow,
                onClick = onToggleCropLock
            )

            // Midrib guide visibility toggle
            HorizontalToggleButton(
                label = "Guide",
                isActive = midribGuideEnabled,
                activeIcon = Icons.Default.Straighten,
                inactiveIcon = Icons.Outlined.Straighten,
                activeColor = Color.Cyan,
                onClick = onToggleMidribGuide
            )

            // Midrib guide lock toggle (only if guide is enabled)
            if (midribGuideEnabled) {
                HorizontalToggleButton(
                    label = "Line",
                    isActive = midribGuideLocked,
                    activeIcon = Icons.Default.Lock,
                    inactiveIcon = Icons.Default.LockOpen,
                    activeColor = Color.Yellow,
                    onClick = onToggleMidribLock
                )
            }

            // Midrib auto-alignment toggle
            HorizontalToggleButton(
                label = "Align",
                isActive = midribAlignmentEnabled,
                activeIcon = Icons.Default.AutoFixHigh,
                inactiveIcon = Icons.Outlined.AutoFixOff,
                activeColor = Color.Cyan,
                onClick = onToggleMidribAlignment
            )
        }
    }
}

@Composable
private fun HorizontalToggleButton(
    label: String,
    isActive: Boolean,
    activeIcon: androidx.compose.ui.graphics.vector.ImageVector,
    inactiveIcon: androidx.compose.ui.graphics.vector.ImageVector,
    activeColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = if (isActive) activeIcon else inactiveIcon,
            contentDescription = "$label toggle",
            tint = if (isActive) activeColor else Color.Gray,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            color = if (isActive) activeColor else Color.Gray,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

