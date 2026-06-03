package com.leafdoc.app.ui.camera

import android.Manifest
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.leafdoc.app.camera.CapturedImage
import com.leafdoc.app.camera.CameraState
import com.leafdoc.app.camera.LensInfo
import com.leafdoc.app.camera.ProCameraController
import com.leafdoc.app.data.model.*
import kotlinx.coroutines.launch

/** Leaf-green accent used for selected/active controls in the camera UI (matches the app theme). */
private val LeafAccent = Color(0xFF60AD5E)

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToResults: (String) -> Unit,
    onNavigateToDashboard: () -> Unit,
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
    val cropRectEnabled by viewModel.cropRectEnabled.collectAsState()
    val midribAlignmentEnabled by viewModel.midribAlignmentEnabled.collectAsState()

    // Manual alignment state
    val showManualAlignment by viewModel.showManualAlignment.collectAsState()
    val alignmentBitmaps by viewModel.alignmentBitmaps.collectAsState()

    // Simple (fast field) mode state
    val simpleMode by viewModel.simpleMode.collectAsState()
    val pendingCapture by viewModel.pendingCapture.collectAsState()
    val simpleFarmerId by viewModel.simpleFarmerId.collectAsState()
    val simpleFieldId by viewModel.simpleFieldId.collectAsState()
    val simpleTreatment by viewModel.simpleTreatment.collectAsState()
    val simpleLeafNumber by viewModel.simpleLeafNumber.collectAsState()

    // User-managed pick lists for capture metadata
    val farmerIdOptions by viewModel.farmerIdOptions.collectAsState()
    val fieldIdOptions by viewModel.fieldIdOptions.collectAsState()
    val treatmentOptions by viewModel.treatmentOptions.collectAsState()

    // Pop-out panel state
    var showLockControlsPanel by remember { mutableStateOf(false) }

    // Focus UI state
    var showFocusPanel by remember { mutableStateOf(false) }
    var focusTapPoint by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

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
    val currentLens by cameraController?.currentLens?.collectAsState() ?: remember { mutableStateOf(null) }
    val selectedResolution by cameraController?.selectedResolution?.collectAsState() ?: remember { mutableStateOf<android.util.Size?>(null) }
    val rawEnabled by cameraController?.rawEnabled?.collectAsState() ?: remember { mutableStateOf(false) }
    val afLocked by cameraController?.afLocked?.collectAsState() ?: remember { mutableStateOf(false) }
    val zoomRatio by cameraController?.zoomRatio?.collectAsState() ?: remember { mutableStateOf(1f) }
    val zoomRange by cameraController?.zoomRange?.collectAsState() ?: remember { mutableStateOf(1f to 1f) }

    val permissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    LaunchedEffect(uiState.sessionComplete) {
        // Navigate when the session is finished — whether the user saved frames only
        // (no stitched image) or stitched them into a panorama.
        if (uiState.sessionComplete) {
            uiState.sessionId?.let { sessionId ->
                onNavigateToResults(sessionId)
                // Reset state after navigation to prevent stale navigation state
                viewModel.resetForNewCapture()
            }
        }
    }

    // Auto-dismiss transient status messages and errors (no Scaffold here, so we show
    // lightweight overlays and clear them after a short delay).
    LaunchedEffect(uiState.message) {
        if (uiState.message != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.clearError()
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
                        // FIT_CENTER shows the entire captured frame (letterboxed top/bottom),
                        // so the preview matches the final image aspect ratio (WYSIWYG).
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        // COMPATIBLE (TextureView) composites correctly through navigation
                        // transitions. The default SurfaceView mode can leave a black screen
                        // when navigating to/from the camera on some devices.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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
                                    focusTapPoint = offset
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

            // Crop Rectangle Overlay (only if enabled; hidden in Simple mode for a clean full-frame shot)
            if (cropRectEnabled && !simpleMode) {
                CropRectangleOverlay(
                    cropRect = cropRect,
                    onCropRectChanged = { viewModel.updateCropRect(it) },
                    isLocked = cropRectLocked,
                    enabled = !uiState.isProcessing,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Midrib Guide Line (horizontal guide for aligning the leaf midrib)
            if (midribGuideEnabled && !simpleMode) {
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

            // Top Bar with pop-out trigger/panel in center and lens selector
            TopBar(
                sessionActive = uiState.sessionActive,
                currentLens = currentLens,
                zoomRatio = zoomRatio,
                zoomRange = zoomRange,
                onZoomSelected = { z -> cameraController?.setZoom(z) },
                selectedResolution = selectedResolution,
                onResolutionSelected = { size -> cameraController?.selectResolution(size) },
                rawSupported = currentLens?.rawSize != null,
                rawEnabled = rawEnabled,
                onToggleRaw = { cameraController?.setRawEnabled(!rawEnabled) },
                focusActive = showFocusPanel || cameraSettings.focusMode != FocusMode.CONTINUOUS || afLocked,
                onToggleFocus = { showFocusPanel = !showFocusPanel },
                segmentCount = uiState.segmentCount,
                onSettingsClick = onNavigateToSettings,
                onDashboardClick = onNavigateToDashboard,
                showLockControlsPanel = showLockControlsPanel,
                onToggleLockControlsPanel = { showLockControlsPanel = !showLockControlsPanel },
                cropRectEnabled = cropRectEnabled,
                cropRectLocked = cropRectLocked,
                midribGuideLocked = midribGuideLocked,
                midribGuideEnabled = midribGuideEnabled,
                midribAlignmentEnabled = midribAlignmentEnabled,
                onToggleCropEnabled = { viewModel.toggleCropRectEnabled() },
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
                simpleMode = simpleMode,
                segmentCount = uiState.segmentCount,
                isProcessing = uiState.isProcessing,
                isStitching = uiState.isStitching,
                cameraState = cameraState,
                lastThumbnail = uiState.lastCapturedThumbnail,
                onGalleryClick = onNavigateToGallery,
                onStartSession = { showSessionDialog = true },
                onCapture = {
                    scope.launch {
                        try {
                            cameraController?.captureImage()?.let { image ->
                                if (simpleMode) viewModel.onSimpleCaptured(image)
                                else viewModel.onImageCaptured(image)
                            }
                        } catch (e: Exception) {
                            viewModel.reportCaptureError(e.message ?: "Capture failed")
                        }
                    }
                },
                onFinishSession = { viewModel.finishSessionFramesOnly() },
                onCancelSession = { if (simpleMode) viewModel.exitSimpleMode() else viewModel.cancelSession() },
                onDeleteLast = { viewModel.deleteLastSegment() },
                onToggleControls = { showProControls = !showProControls },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            )

            // Captured Segments Strip (hidden while the focus panel is open to avoid overlap)
            if (capturedSegments.isNotEmpty() && !showFocusPanel) {
                SegmentsStrip(
                    segments = capturedSegments,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, bottom = 140.dp)
                )
            }

            // Simple-mode metadata bar (always-visible, editable)
            if (simpleMode) {
                SimpleMetadataBar(
                    farmerId = simpleFarmerId,
                    fieldId = simpleFieldId,
                    treatment = simpleTreatment,
                    leafNumber = simpleLeafNumber,
                    farmerOptions = farmerIdOptions,
                    fieldOptions = fieldIdOptions,
                    treatmentOptions = treatmentOptions,
                    onFarmerIdChange = { viewModel.setSimpleFarmerId(it) },
                    onFieldIdChange = { viewModel.setSimpleFieldId(it) },
                    onTreatmentChange = { viewModel.setSimpleTreatment(it) },
                    onLeafNumberChange = { viewModel.setSimpleLeafNumber(it) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 64.dp, start = 12.dp, end = 12.dp)
                )
            }

            // Tap-to-focus ring
            focusTapPoint?.let { pt ->
                val ringHalfPx = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }
                Box(
                    modifier = Modifier
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                (pt.x - ringHalfPx).toInt(),
                                (pt.y - ringHalfPx).toInt()
                            )
                        }
                        .size(72.dp)
                        .border(2.dp, LeafAccent, CircleShape)
                )
                LaunchedEffect(pt) {
                    kotlinx.coroutines.delay(800)
                    focusTapPoint = null
                }
            }

            // Focus options panel
            if (showFocusPanel) {
                FocusPanel(
                    focusMode = cameraSettings.focusMode,
                    focusDistance = cameraSettings.focusDistance,
                    maxFocusDistance = cameraCapabilities?.minFocusDistance ?: 0f,
                    afLocked = afLocked,
                    onFocusModeChange = { viewModel.updateFocusMode(it) },
                    onFocusDistanceChange = { viewModel.updateFocusDistance(it) },
                    onAfLockToggle = { cameraController?.setAfLocked(!afLocked) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 150.dp, start = 12.dp, end = 12.dp)
                )
            }

            // Status Message — top-anchored; in Simple mode it sits below the metadata bar so it
            // never collides with the focus panel / controls at the bottom.
            uiState.message?.let { message ->
                StatusMessage(
                    message = message,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = if (simpleMode) 224.dp else 72.dp, start = 16.dp, end = 16.dp)
                )
            }

            // Error overlay (red), shown over the preview and auto-dismissed.
            uiState.error?.let { error ->
                StatusMessage(
                    message = error,
                    color = Color.Red.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = if (simpleMode) 224.dp else 72.dp, start = 16.dp, end = 16.dp)
                )
            }

            // Processing Overlay
            if (uiState.isStitching) {
                ProcessingOverlay(
                    message = "Creating panorama...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Simple-mode capture review (Retake / Export)
            if (simpleMode && pendingCapture != null) {
                SimpleReviewOverlay(
                    image = pendingCapture!!,
                    leafNumber = simpleLeafNumber,
                    isProcessing = uiState.isProcessing,
                    onRetake = { viewModel.retakeSimple() },
                    onExport = { viewModel.exportSimpleCapture() },
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
            farmerOptions = farmerIdOptions,
            fieldOptions = fieldIdOptions,
            treatmentOptions = treatmentOptions,
            onDismiss = { showSessionDialog = false },
            onConfirm = { type, farmerId, fieldId, treatment, leafNumber ->
                if (type == SessionType.SIMPLE) {
                    viewModel.startSimpleMode(farmerId, fieldId, treatment, leafNumber)
                } else {
                    viewModel.startNewSession(farmerId, fieldId, treatment, leafNumber)
                }
                showSessionDialog = false
            }
        )
    }

    // Manual Alignment Screen
    if (showManualAlignment && alignmentBitmaps.isNotEmpty()) {
        ManualAlignmentScreen(
            segments = alignmentBitmaps,
            overlapPercent = overlapPercentage / 100f,
            onAutoAlign = { viewModel.getAutoAlignOffsets() },
            onGeneratePreview = { offsets -> viewModel.generatePreview(offsets) },
            onConfirm = { offsets -> viewModel.confirmAlignment(offsets) },
            onDismiss = { viewModel.cancelAlignment() }
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
    onDashboardClick: () -> Unit,
    currentLens: LensInfo?,
    zoomRatio: Float,
    zoomRange: Pair<Float, Float>,
    onZoomSelected: (Float) -> Unit,
    selectedResolution: android.util.Size?,
    onResolutionSelected: (android.util.Size?) -> Unit,
    rawSupported: Boolean,
    rawEnabled: Boolean,
    onToggleRaw: () -> Unit,
    focusActive: Boolean,
    onToggleFocus: () -> Unit,
    showLockControlsPanel: Boolean,
    onToggleLockControlsPanel: () -> Unit,
    cropRectEnabled: Boolean,
    cropRectLocked: Boolean,
    midribGuideLocked: Boolean,
    midribGuideEnabled: Boolean,
    midribAlignmentEnabled: Boolean,
    onToggleCropEnabled: () -> Unit,
    onToggleCropLock: () -> Unit,
    onToggleMidribLock: () -> Unit,
    onToggleMidribGuide: () -> Unit,
    onToggleMidribAlignment: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Dashboard / overview (top-left)
        IconButton(
            onClick = onDashboardClick,
            modifier = Modifier
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Insights,
                contentDescription = "Overview",
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
        } else {
            // Toggle for the overlay-controls row (crop / midrib / align) shown BELOW the bar.
            IconButton(
                onClick = onToggleLockControlsPanel,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (showLockControlsPanel) LeafAccent.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (showLockControlsPanel) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle overlay controls",
                    tint = if (showLockControlsPanel) Color.Black else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Zoom/lens selector — on modern phones the ultra-wide/main/tele lenses are fused
        // behind one logical camera and selected by zoom ratio (stock-camera 0.6x/1x/3x/5x).
        if (zoomRange.second > zoomRange.first) {
            ZoomSelector(
                zoomRatio = zoomRatio,
                zoomRange = zoomRange,
                onZoomSelected = onZoomSelected
            )
        }

        // Resolution / megapixel selector (if the current lens offers multiple sizes)
        val lensSizes = currentLens?.stillSizes ?: emptyList()
        if (lensSizes.size > 1) {
            ResolutionSelector(
                sizes = lensSizes,
                selected = selectedResolution,
                onSelected = onResolutionSelected
            )
        }

        // RAW/DNG toggle (only when the current lens supports RAW)
        if (rawSupported) {
            IconButton(
                onClick = onToggleRaw,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (rawEnabled) LeafAccent.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f),
                        CircleShape
                    )
            ) {
                Text(
                    text = "RAW",
                    color = if (rawEnabled) Color.Black else Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Focus options toggle
        IconButton(
            onClick = onToggleFocus,
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (focusActive) LeafAccent.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f),
                    CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.CenterFocusStrong,
                contentDescription = "Focus options",
                tint = if (focusActive) Color.Black else Color.White,
                modifier = Modifier.size(22.dp)
            )
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

        // Overlay-controls panel — its own full-width row BELOW the bar, so it never
        // overlaps the lens / resolution / RAW / focus / settings icons.
        if (!sessionActive && showLockControlsPanel) {
            HorizontalLockControlsPanel(
                cropRectEnabled = cropRectEnabled,
                cropRectLocked = cropRectLocked,
                midribGuideLocked = midribGuideLocked,
                midribGuideEnabled = midribGuideEnabled,
                midribAlignmentEnabled = midribAlignmentEnabled,
                onToggleCropEnabled = onToggleCropEnabled,
                onToggleCropLock = onToggleCropLock,
                onToggleMidribLock = onToggleMidribLock,
                onToggleMidribGuide = onToggleMidribGuide,
                onToggleMidribAlignment = onToggleMidribAlignment,
                onClose = onToggleLockControlsPanel,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun BottomControls(
    sessionActive: Boolean,
    simpleMode: Boolean,
    segmentCount: Int,
    isProcessing: Boolean,
    isStitching: Boolean,
    cameraState: CameraState,
    lastThumbnail: String?,
    onGalleryClick: () -> Unit,
    onStartSession: () -> Unit,
    onCapture: () -> Unit,
    onFinishSession: () -> Unit,
    onCancelSession: () -> Unit,
    onDeleteLast: () -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    // "Capture mode" = a session is active OR we're in Simple mode (shutter takes a photo).
    val captureMode = sessionActive || simpleMode
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
            if (captureMode) {
                IconButton(
                    onClick = if (sessionActive && segmentCount > 0) onDeleteLast else onCancelSession,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (sessionActive && segmentCount > 0) Icons.Default.Undo else Icons.Default.Close,
                        contentDescription = if (sessionActive && segmentCount > 0) "Delete last" else "Cancel",
                        tint = Color.White
                    )
                }
            } else {
                // Idle: bottom-left opens the gallery
                IconButton(
                    onClick = onGalleryClick,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "Gallery",
                        tint = Color.White
                    )
                }
            }

            // Capture button (shutter in capture mode) OR a labeled "Start Session" button.
            if (captureMode) {
                CaptureButton(
                    enabled = !isProcessing && cameraState == CameraState.Ready,
                    sessionActive = true,
                    isCapturing = cameraState == CameraState.Capturing,
                    onCapture = onCapture,
                    modifier = Modifier.size(80.dp)
                )
            } else {
                Button(
                    onClick = onStartSession,
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Session", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            // Finish / Pro controls (detailed sessions only)
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
private fun SimpleMetadataBar(
    farmerId: String,
    fieldId: String,
    treatment: String,
    leafNumber: Int,
    farmerOptions: List<String>,
    fieldOptions: List<String>,
    treatmentOptions: List<String>,
    onFarmerIdChange: (String) -> Unit,
    onFieldIdChange: (String) -> Unit,
    onTreatmentChange: (String) -> Unit,
    onLeafNumberChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.White,
        unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
        focusedLabelColor = Color.White,
        unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
        cursorColor = Color.White,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionComboField(
                    value = farmerId, onValueChange = onFarmerIdChange, label = "Farmer",
                    options = farmerOptions, colors = fieldColors, modifier = Modifier.weight(1f)
                )
                OptionComboField(
                    value = fieldId, onValueChange = onFieldIdChange, label = "Field",
                    options = fieldOptions, colors = fieldColors, modifier = Modifier.weight(1f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OptionComboField(
                    value = treatment, onValueChange = onTreatmentChange, label = "Treatment",
                    options = treatmentOptions, colors = fieldColors, modifier = Modifier.weight(1f)
                )
                // Leaf number stepper
                IconButton(onClick = { onLeafNumberChange(leafNumber - 1) }) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease leaf", tint = Color.White)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Leaf", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                    Text("$leafNumber", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                IconButton(onClick = { onLeafNumberChange(leafNumber + 1) }) {
                    Icon(Icons.Default.Add, contentDescription = "Increase leaf", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SimpleReviewOverlay(
    image: CapturedImage,
    leafNumber: Int,
    isProcessing: Boolean,
    onRetake: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val bmp = image.bitmap
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Captured leaf",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else if (image.filePath != null) {
            AsyncImage(
                model = image.filePath,
                contentDescription = "Captured leaf",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
        ) {
            Text(
                text = "Leaf $leafNumber — review",
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(
                onClick = onRetake,
                enabled = !isProcessing,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retake")
            }
            Button(onClick = onExport, enabled = !isProcessing) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Exporting...")
                } else {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export")
                }
            }
        }
    }
}

@Composable
private fun FocusPanel(
    focusMode: FocusMode,
    focusDistance: Float,
    maxFocusDistance: Float,
    afLocked: Boolean,
    onFocusModeChange: (FocusMode) -> Unit,
    onFocusDistanceChange: (Float) -> Unit,
    onAfLockToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Focus", color = Color.White, fontWeight = FontWeight.Bold)
                FilterChip(
                    selected = afLocked,
                    onClick = onAfLockToggle,
                    label = { Text("AF Lock") },
                    leadingIcon = {
                        Icon(
                            if (afLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FocusMode.entries.forEach { mode ->
                    FilterChip(
                        selected = focusMode == mode,
                        onClick = { onFocusModeChange(mode) },
                        label = { Text(mode.displayName) }
                    )
                }
            }
            if (focusMode == FocusMode.MANUAL && maxFocusDistance > 0f) {
                Text(
                    "Far  ←  focus distance  →  Near",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Slider(
                    value = (if (focusDistance >= 0f) focusDistance else 0f).coerceIn(0f, maxFocusDistance),
                    onValueChange = onFocusDistanceChange,
                    valueRange = 0f..maxFocusDistance
                )
            }
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
    modifier: Modifier = Modifier,
    color: Color = Color.Black.copy(alpha = 0.7f)
) {
    Surface(
        modifier = modifier,
        color = color,
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
    farmerOptions: List<String>,
    fieldOptions: List<String>,
    treatmentOptions: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (type: SessionType, farmerId: String, fieldId: String, treatment: String, leafNumber: Int) -> Unit
) {
    var sessionType by remember { mutableStateOf(SessionType.SIMPLE) }
    var farmerId by remember { mutableStateOf(defaultFarmerId) }
    var fieldId by remember { mutableStateOf(defaultFieldId) }
    var treatment by remember { mutableStateOf("") }
    var leafNumber by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start New Session") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text("Session Type", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sessionType == SessionType.SIMPLE,
                        onClick = { sessionType = SessionType.SIMPLE },
                        label = { Text("Simple") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = sessionType == SessionType.DETAILED,
                        onClick = { sessionType = SessionType.DETAILED },
                        label = { Text("Detailed") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = if (sessionType == SessionType.SIMPLE)
                        "One image per leaf — edit details, capture, export, repeat."
                    else
                        "Multiple frames per leaf — optional stitching and AI diagnosis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OptionComboField(
                    value = farmerId,
                    onValueChange = { farmerId = it },
                    label = "Farmer ID (optional)",
                    options = farmerOptions,
                    modifier = Modifier.fillMaxWidth()
                )
                OptionComboField(
                    value = fieldId,
                    onValueChange = { fieldId = it },
                    label = "Field ID (optional)",
                    options = fieldOptions,
                    modifier = Modifier.fillMaxWidth()
                )
                OptionComboField(
                    value = treatment,
                    onValueChange = { treatment = it },
                    label = "Treatment (optional)",
                    options = treatmentOptions,
                    modifier = Modifier.fillMaxWidth()
                )
                val dialogFocus = LocalFocusManager.current
                OutlinedTextField(
                    value = leafNumber,
                    onValueChange = { leafNumber = it.filter { c -> c.isDigit() } },
                    label = { Text("Leaf Number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { dialogFocus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        sessionType,
                        farmerId,
                        fieldId,
                        treatment,
                        leafNumber.toIntOrNull() ?: 1
                    )
                }
            ) {
                Text(if (sessionType == SessionType.SIMPLE) "Start" else "Start Capture")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionComboField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    colors: androidx.compose.material3.TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    ExposedDropdownMenuBox(
        expanded = expanded && options.isNotEmpty(),
        onExpandedChange = { if (options.isNotEmpty()) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 11.sp) },
            singleLine = true,
            // "Done" dismisses the keyboard and clears focus so the cursor stops blinking.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            trailingIcon = {
                if (options.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = colors,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        if (options.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = { onValueChange(opt); expanded = false }
                    )
                }
            }
        }
    }
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
    cropRectEnabled: Boolean,
    cropRectLocked: Boolean,
    midribGuideLocked: Boolean,
    midribGuideEnabled: Boolean,
    midribAlignmentEnabled: Boolean,
    onToggleCropEnabled: () -> Unit,
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
            // (The top-bar arrow toggles this panel, so no separate close button is needed.)

            // Crop rectangle enabled toggle
            HorizontalToggleButton(
                label = "Crop",
                isActive = cropRectEnabled,
                activeIcon = Icons.Default.CropFree,
                inactiveIcon = Icons.Outlined.CropFree,
                activeColor = LeafAccent,
                onClick = onToggleCropEnabled
            )

            // Crop rectangle lock toggle (only if crop is enabled)
            if (cropRectEnabled) {
                HorizontalToggleButton(
                    label = "Lock",
                    isActive = cropRectLocked,
                    activeIcon = Icons.Default.Lock,
                    inactiveIcon = Icons.Default.LockOpen,
                    activeColor = Color.Yellow,
                    onClick = onToggleCropLock
                )
            }

            // Midrib guide visibility toggle
            HorizontalToggleButton(
                label = "Guide",
                isActive = midribGuideEnabled,
                activeIcon = Icons.Default.Straighten,
                inactiveIcon = Icons.Outlined.Straighten,
                activeColor = LeafAccent,
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
                activeColor = LeafAccent,
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

/**
 * Lens selector dropdown for switching between available camera lenses
 */
@Composable
private fun zoomLabel(z: Float): String =
    if (z % 1f == 0f) "${z.toInt()}×" else "${"%.1f".format(z)}×"

@Composable
private fun ZoomSelector(
    zoomRatio: Float,
    zoomRange: Pair<Float, Float>,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val (min, max) = zoomRange
    // Standard zoom stops (ultra-wide → tele), clamped to what this camera supports.
    val presets = remember(min, max) {
        (listOf(min, 0.6f, 1f, 2f, 3f, 5f, 10f, max))
            .filter { it in min..max }
            .distinctBy { "%.1f".format(it) }
            .sorted()
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Text(
                text = zoomLabel(zoomRatio),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.Black.copy(alpha = 0.9f))
        ) {
            presets.forEach { z ->
                val isSel = "%.1f".format(z) == "%.1f".format(zoomRatio)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = zoomLabel(z),
                            color = if (isSel) LeafAccent else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = { onZoomSelected(z); expanded = false }
                )
            }
        }
    }
}

private fun megapixelLabel(size: android.util.Size): String {
    val mp = (size.width.toLong() * size.height) / 1_000_000.0
    return if (mp >= 1.0) "${"%.0f".format(mp)}MP" else "${size.width}×${size.height}"
}

@Composable
private fun ResolutionSelector(
    sizes: List<android.util.Size>,
    selected: android.util.Size?,
    onSelected: (android.util.Size?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val maxSize = sizes.firstOrNull() // sizes are sorted largest-first

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            // When on Auto, show the actual maximum MP rather than just "Max".
            Text(
                text = (selected ?: maxSize)?.let { megapixelLabel(it) } ?: "Max",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.Black.copy(alpha = 0.9f))
        ) {
            // Auto = largest available (show the real max MP next to it)
            DropdownMenuItem(
                text = {
                    Text(
                        text = maxSize?.let { "Auto (Max) · ${megapixelLabel(it)} · ${it.width}×${it.height}" }
                            ?: "Auto (Max)",
                        color = if (selected == null) LeafAccent else Color.White,
                        fontSize = 14.sp,
                        fontWeight = if (selected == null) FontWeight.Bold else FontWeight.Normal
                    )
                },
                onClick = { onSelected(null); expanded = false }
            )
            sizes.forEach { size ->
                val isSel = selected?.width == size.width && selected?.height == size.height
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${megapixelLabel(size)}  ·  ${size.width}×${size.height}",
                            color = if (isSel) LeafAccent else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = { onSelected(size); expanded = false }
                )
            }
        }
    }
}


