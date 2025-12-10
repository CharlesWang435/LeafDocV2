package com.leafdoc.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.leafdoc.app.data.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val cameraSettings by viewModel.cameraSettings.collectAsState()
    val exportSettings by viewModel.exportSettings.collectAsState()
    val farmerId by viewModel.farmerId.collectAsState()
    val fieldId by viewModel.fieldId.collectAsState()
    val overlapPercentage by viewModel.overlapPercentage.collectAsState()
    val autoSaveSegments by viewModel.autoSaveSegments.collectAsState()
    val vibrateOnCapture by viewModel.vibrateOnCapture.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    // Midrib alignment settings
    val midribAlignmentEnabled by viewModel.midribAlignmentEnabled.collectAsState()
    val midribSearchTolerance by viewModel.midribSearchTolerance.collectAsState()
    val midribGuideEnabled by viewModel.midribGuideEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // User Information Section
            SettingsSection(title = "User Information") {
                // Local state to prevent cursor jumping
                var farmerIdInput by remember { mutableStateOf(farmerId) }
                var fieldIdInput by remember { mutableStateOf(fieldId) }

                // Sync local state with flow state only when flow changes
                LaunchedEffect(farmerId) {
                    if (farmerIdInput != farmerId) {
                        farmerIdInput = farmerId
                    }
                }
                LaunchedEffect(fieldId) {
                    if (fieldIdInput != fieldId) {
                        fieldIdInput = fieldId
                    }
                }

                SettingsTextField(
                    label = "Default Farmer ID",
                    value = farmerIdInput,
                    onValueChange = {
                        farmerIdInput = it
                        viewModel.updateFarmerId(it)
                    },
                    icon = Icons.Default.Person
                )

                SettingsTextField(
                    label = "Default Field ID",
                    value = fieldIdInput,
                    onValueChange = {
                        fieldIdInput = it
                        viewModel.updateFieldId(it)
                    },
                    icon = Icons.Default.Landscape
                )
            }

            // Camera Settings Section
            SettingsSection(title = "Camera") {
                // Resolution
                SettingsDropdown(
                    label = "Default Resolution",
                    value = cameraSettings.resolution.displayName,
                    options = ResolutionMode.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.updateResolution(ResolutionMode.entries[index])
                    },
                    icon = Icons.Default.HighQuality
                )

                // Grid Overlay
                SettingsDropdown(
                    label = "Grid Overlay",
                    value = cameraSettings.gridOverlay.displayName,
                    options = GridOverlayType.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.updateGridOverlay(GridOverlayType.entries[index])
                    },
                    icon = Icons.Default.GridOn
                )

                // Histogram
                SettingsSwitch(
                    label = "Show Histogram",
                    checked = cameraSettings.showHistogram,
                    onCheckedChange = { viewModel.updateShowHistogram(it) },
                    icon = Icons.Default.BarChart
                )

                // Focus Peaking
                SettingsSwitch(
                    label = "Focus Peaking",
                    checked = cameraSettings.showFocusPeaking,
                    onCheckedChange = { viewModel.updateShowFocusPeaking(it) },
                    icon = Icons.Default.CenterFocusStrong
                )

                // Zebras
                SettingsSwitch(
                    label = "Zebra Stripes (Overexposure)",
                    checked = cameraSettings.showZebras,
                    onCheckedChange = { viewModel.updateShowZebras(it) },
                    icon = Icons.Default.Exposure
                )

                if (cameraSettings.showZebras) {
                    SettingsSlider(
                        label = "Zebra Threshold",
                        value = cameraSettings.zebraThreshold.toFloat(),
                        valueRange = 80f..100f,
                        valueText = "${cameraSettings.zebraThreshold}%",
                        onValueChange = { viewModel.updateZebraThreshold(it.toInt()) }
                    )
                }
            }

            // Stitching Settings Section
            SettingsSection(title = "Stitching") {
                SettingsSlider(
                    label = "Blend Overlap",
                    value = overlapPercentage.toFloat(),
                    valueRange = 5f..25f,
                    valueText = "$overlapPercentage%",
                    onValueChange = { viewModel.updateOverlapPercentage(it.toInt()) }
                )

                Text(
                    text = "Overlap region for blending between segments. 10-15% recommended for smooth transitions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Midrib Alignment Section
            SettingsSection(title = "Midrib Alignment") {
                SettingsSwitch(
                    label = "Auto-Align Midrib",
                    description = "Automatically align leaf segments by detecting and matching the midrib (central vein)",
                    checked = midribAlignmentEnabled,
                    onCheckedChange = { viewModel.updateMidribAlignmentEnabled(it) },
                    icon = Icons.Default.AutoFixHigh
                )

                if (midribAlignmentEnabled) {
                    SettingsSlider(
                        label = "Search Tolerance",
                        value = midribSearchTolerance.toFloat(),
                        valueRange = 20f..80f,
                        valueText = "$midribSearchTolerance%",
                        onValueChange = { viewModel.updateMidribSearchTolerance(it.toInt()) }
                    )

                    Text(
                        text = "How much of the image height to search for the midrib. Higher values handle more vertical drift but may be slower.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                SettingsSwitch(
                    label = "Show Midrib Guide",
                    description = "Display a horizontal guide line on the camera preview to help align the leaf midrib",
                    checked = midribGuideEnabled,
                    onCheckedChange = { viewModel.updateMidribGuideEnabled(it) },
                    icon = Icons.Default.Straighten
                )
            }

            // Export Settings Section
            SettingsSection(title = "Export") {
                // Format
                SettingsDropdown(
                    label = "Default Format",
                    value = exportSettings.format.displayName,
                    options = ImageFormat.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.updateExportFormat(ImageFormat.entries[index])
                    },
                    icon = Icons.Default.Image
                )

                // Quality (for JPEG)
                if (exportSettings.format.supportsQuality) {
                    SettingsSlider(
                        label = "JPEG Quality",
                        value = exportSettings.quality.toFloat(),
                        valueRange = 50f..100f,
                        valueText = "${exportSettings.quality}%",
                        onValueChange = { viewModel.updateExportQuality(it.toInt()) }
                    )
                }

                // Export Location
                SettingsDropdown(
                    label = "Save Location",
                    value = exportSettings.exportLocation.displayName,
                    options = ExportLocation.entries.map { it.displayName },
                    onSelect = { index ->
                        viewModel.updateExportLocation(ExportLocation.entries[index])
                    },
                    icon = Icons.Default.Folder
                )

                // Include Metadata
                SettingsSwitch(
                    label = "Include EXIF Metadata",
                    checked = exportSettings.includeMetadata,
                    onCheckedChange = { viewModel.updateIncludeMetadata(it) },
                    icon = Icons.Default.Info
                )
            }

            // App Behavior Section
            SettingsSection(title = "Behavior") {
                SettingsSwitch(
                    label = "Vibrate on Capture",
                    checked = vibrateOnCapture,
                    onCheckedChange = { viewModel.updateVibrateOnCapture(it) },
                    icon = Icons.Default.Vibration
                )

                SettingsSwitch(
                    label = "Keep Screen On",
                    checked = keepScreenOn,
                    onCheckedChange = { viewModel.updateKeepScreenOn(it) },
                    icon = Icons.Default.Brightness7
                )

                SettingsSwitch(
                    label = "Auto-save Segments",
                    checked = autoSaveSegments,
                    onCheckedChange = { viewModel.updateAutoSaveSegments(it) },
                    icon = Icons.Default.Save
                )
            }

            // Storage Section
            SettingsSection(title = "Storage") {
                storageInfo?.let { info ->
                    StorageInfoRow(label = "Segments", value = info.formatSize(info.segmentsBytes))
                    StorageInfoRow(label = "Stitched Images", value = info.formatSize(info.stitchedBytes))
                    StorageInfoRow(label = "Thumbnails", value = info.formatSize(info.thumbnailBytes))
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    StorageInfoRow(label = "Total", value = info.formatSize(info.totalBytes), bold = true)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { viewModel.clearThumbnailCache() },
                    enabled = !uiState.isClearingCache,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    if (uiState.isClearingCache) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Clear Thumbnail Cache")
                }
            }

            // About Section
            SettingsSection(title = "About") {
                SettingsInfoRow(
                    label = "Version",
                    value = "1.0.0"
                )
                SettingsInfoRow(
                    label = "Build",
                    value = "Production"
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        content()
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = if (icon != null) 40.dp else 0.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
                    },
                    leadingIcon = if (option == value) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingsInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun StorageInfoRow(
    label: String,
    value: String,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}
