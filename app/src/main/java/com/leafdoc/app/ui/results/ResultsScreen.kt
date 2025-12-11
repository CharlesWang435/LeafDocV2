package com.leafdoc.app.ui.results

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.leafdoc.app.data.model.*
import com.leafdoc.app.ui.components.ClickableZoomableImage
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ResultsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val session by viewModel.session.collectAsState()
    val segments by viewModel.segments.collectAsState()
    val diagnosis by viewModel.diagnosis.collectAsState()
    val exportSettings by viewModel.exportSettings.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Handle session deletion
    LaunchedEffect(uiState.sessionDeleted) {
        if (uiState.sessionDeleted) {
            onNavigateBack()
        }
    }

    // Handle share intent
    LaunchedEffect(uiState.shareUri) {
        uiState.shareUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Leaf Image"))
            viewModel.clearShareUri()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Leaf Analysis") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.shareImage() }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { showDeleteConfirmation = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (session == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                // Image Preview
                ImagePreviewCard(
                    imagePath = session!!.stitchedImagePath,
                    segmentCount = session!!.segmentCount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.5f)
                        .padding(16.dp)
                )

                // Session Info
                SessionInfoCard(
                    session = session!!,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Diagnosis Section
                DiagnosisCard(
                    session = session!!,
                    diagnosis = diagnosis,
                    isAnalyzing = uiState.isAnalyzing,
                    onAnalyze = { viewModel.analyzeDiagnosis() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Export Section
                ExportCard(
                    settings = exportSettings,
                    isExporting = uiState.isExporting,
                    onExport = { showExportDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Export Dialog
    if (showExportDialog) {
        ExportDialog(
            currentFormat = exportSettings.format,
            currentQuality = exportSettings.quality,
            onDismiss = { showExportDialog = false },
            onExport = { format ->
                viewModel.exportImage(format)
                showExportDialog = false
            }
        )
    }

    // Delete Confirmation
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Session") },
            text = { Text("Are you sure you want to delete this leaf session? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSession()
                        showDeleteConfirmation = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Error/Success Snackbars
    uiState.error?.let { error ->
        // Show error
    }
    uiState.message?.let { message ->
        // Show message
    }
}

@Composable
private fun ImagePreviewCard(
    imagePath: String?,
    segmentCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (imagePath != null) {
                ClickableZoomableImage(
                    imagePath = imagePath,
                    contentDescription = "Stitched leaf image - tap to zoom",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Image not available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Segment count badge
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$segmentCount segments",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionInfoCard(
    session: LeafSession,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MMMM dd, yyyy 'at' HH:mm", Locale.getDefault()) }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Session Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            InfoRow(label = "Leaf Number", value = "#${session.leafNumber}")
            InfoRow(label = "Captured", value = dateFormat.format(Date(session.createdAt)))

            if (session.farmerId.isNotEmpty()) {
                InfoRow(label = "Farmer ID", value = session.farmerId)
            }
            if (session.fieldId.isNotEmpty()) {
                InfoRow(label = "Field ID", value = session.fieldId)
            }

            if (session.latitude != null && session.longitude != null) {
                InfoRow(
                    label = "Location",
                    value = "${"%.6f".format(session.latitude)}, ${"%.6f".format(session.longitude)}"
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
        Text(
            text = value,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun DiagnosisCard(
    session: LeafSession,
    diagnosis: DiagnosisDisplay?,
    isAnalyzing: Boolean,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI Diagnosis",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                when (session.diagnosisStatus) {
                    DiagnosisStatus.COMPLETED -> {
                        Surface(
                            color = Color.Green.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.Green,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Analyzed",
                                    color = Color.Green,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    DiagnosisStatus.FAILED -> {
                        Surface(
                            color = Color.Red.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Failed",
                                color = Color.Red,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (diagnosis != null) {
                // Health Score
                HealthScoreIndicator(
                    score = diagnosis.healthScore,
                    isHealthy = diagnosis.isHealthy
                )

                // Leaf Description - shown right after health score
                if (!diagnosis.leafDescription.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Leaf Description",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = diagnosis.leafDescription,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary diagnosis
                Text(
                    text = "Primary Finding",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = diagnosis.primaryDiagnosis ?: "Unknown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )

                // Confidence
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { diagnosis.confidence / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (diagnosis.isHealthy) Color.Green else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "${diagnosis.confidence}% confidence",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Suggestions
                if (diagnosis.suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Recommendations",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    diagnosis.suggestions.forEach { suggestion ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(text = "•", modifier = Modifier.padding(end = 8.dp))
                            Text(text = suggestion, fontSize = 14.sp)
                        }
                    }
                }
            } else if (session.diagnosisStatus == DiagnosisStatus.PENDING) {
                // Analyze button
                Button(
                    onClick = onAnalyze,
                    enabled = !isAnalyzing && session.stitchedImagePath != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyzing...")
                    } else {
                        Icon(Icons.Default.Science, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyze with AI")
                    }
                }

                Text(
                    text = "Send the stitched image to our cloud AI for disease detection and health assessment.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else if (session.diagnosisStatus == DiagnosisStatus.PROCESSING ||
                       session.diagnosisStatus == DiagnosisStatus.UPLOADING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Processing...")
                }
            } else if (session.diagnosisStatus == DiagnosisStatus.FAILED) {
                Button(
                    onClick = onAnalyze,
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retry Analysis")
                }
            }
        }
    }
}

@Composable
private fun HealthScoreIndicator(
    score: Int,
    isHealthy: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    if (isHealthy) Color.Green.copy(alpha = 0.2f)
                    else Color.Red.copy(alpha = 0.2f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$score",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHealthy) Color.Green else Color.Red
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = if (isHealthy) "Healthy" else "Issues Detected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isHealthy) Color.Green else Color.Red
            )
            Text(
                text = "Health Score",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExportCard(
    settings: ExportSettings,
    isExporting: Boolean,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Export Image",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Format",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = settings.format.displayName,
                    fontWeight = FontWeight.Medium
                )
            }

            if (settings.format.supportsQuality) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Quality",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${settings.quality}%",
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onExport,
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Exporting...")
                } else {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export to Gallery")
                }
            }
        }
    }
}

@Composable
private fun ExportDialog(
    currentFormat: ImageFormat,
    currentQuality: Int,
    onDismiss: () -> Unit,
    onExport: (ImageFormat) -> Unit
) {
    var selectedFormat by remember { mutableStateOf(currentFormat) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Options") },
        text = {
            Column {
                Text(
                    text = "Select format:",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                ImageFormat.entries.forEach { format ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedFormat == format,
                            onClick = { selectedFormat = format }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = format.displayName)
                            Text(
                                text = when (format) {
                                    ImageFormat.JPEG -> "Smaller file size, adjustable quality"
                                    ImageFormat.PNG -> "Lossless, larger file size"
                                    ImageFormat.TIFF -> "Best for scientific analysis"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExport(selectedFormat) }) {
                Text("Export")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
