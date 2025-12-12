package com.leafdoc.app.ui.camera

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fullscreen dialog for manual Y-axis alignment of leaf segments before stitching.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAlignmentScreen(
    segments: List<Bitmap>,
    overlapPercent: Float,
    onAutoAlign: suspend () -> List<Int>,
    onGeneratePreview: suspend (List<Int>) -> Bitmap?,
    onConfirm: (List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    val offsets = remember { mutableStateListOf<Int>().apply { addAll(List(segments.size) { 0 }) } }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGeneratingPreview by remember { mutableStateOf(false) }
    var isAutoAligning by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }

    // Scroll states for synchronized scrolling
    val previewScrollState = rememberScrollState()
    val segmentsListState = rememberLazyListState()

    // Track preview width for scroll synchronization
    var previewContentWidth by remember { mutableIntStateOf(0) }
    var previewViewportWidth by remember { mutableIntStateOf(0) }

    // Generate initial preview
    LaunchedEffect(Unit) {
        isGeneratingPreview = true
        previewBitmap = onGeneratePreview(offsets.toList())
        isGeneratingPreview = false
    }

    // Sync scroll from segments to preview
    LaunchedEffect(segmentsListState.firstVisibleItemIndex, segmentsListState.firstVisibleItemScrollOffset) {
        if (segments.isNotEmpty() && previewContentWidth > previewViewportWidth) {
            val totalItems = segments.size
            val scrollFraction = if (totalItems > 1) {
                (segmentsListState.firstVisibleItemIndex +
                    segmentsListState.firstVisibleItemScrollOffset / 200f) / (totalItems - 1).coerceAtLeast(1)
            } else 0f

            val maxScroll = (previewContentWidth - previewViewportWidth).coerceAtLeast(0)
            val targetScroll = (scrollFraction * maxScroll).toInt().coerceIn(0, maxScroll)
            previewScrollState.scrollTo(targetScroll)
        }
    }

    fun updatePreview() {
        previewJob?.cancel()
        previewJob = coroutineScope.launch {
            delay(150)
            isGeneratingPreview = true
            val newPreview = onGeneratePreview(offsets.toList())
            previewBitmap?.let { if (!it.isRecycled) it.recycle() }
            previewBitmap = newPreview
            isGeneratingPreview = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Top Bar
                TopAppBar(
                    title = {
                        Column {
                            Text("Align Segments", fontWeight = FontWeight.Bold)
                            Text(
                                "${segments.size} segments",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Preview Section
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Live Preview", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            AnimatedVisibility(visible = isGeneratingPreview, enter = fadeIn(), exit = fadeOut()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Updating...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black)
                                .onSizeChanged { previewViewportWidth = it.width },
                            contentAlignment = Alignment.Center
                        ) {
                            if (previewBitmap != null && !previewBitmap!!.isRecycled) {
                                Image(
                                    bitmap = previewBitmap!!.asImageBitmap(),
                                    contentDescription = "Preview",
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .horizontalScroll(previewScrollState)
                                        .onSizeChanged { previewContentWidth = it.width },
                                    contentScale = ContentScale.FillHeight
                                )
                            } else {
                                Text("Loading...", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // Segments Section with Action Buttons
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Adjust Y Position",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        Text(
                            "Use +/- to shift segments (swipe to see all)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))

                        LazyRow(
                            state = segmentsListState,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(segments) { index, bitmap ->
                                SegmentAdjustmentItem(
                                    bitmap = bitmap,
                                    index = index,
                                    offset = offsets.getOrElse(index) { 0 },
                                    onOffsetChange = { newOffset ->
                                        offsets[index] = newOffset
                                        updatePreview()
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Action Buttons inside the card
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        isAutoAligning = true
                                        val autoOffsets = onAutoAlign()
                                        if (autoOffsets.size == offsets.size) {
                                            autoOffsets.forEachIndexed { index, offset ->
                                                offsets[index] = offset
                                            }
                                            updatePreview()
                                        }
                                        isAutoAligning = false
                                    }
                                },
                                enabled = !isAutoAligning,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isAutoAligning) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(if (isAutoAligning) "Detecting..." else "Auto Align")
                            }

                            Button(
                                onClick = { onConfirm(offsets.toList()) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                            ) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Confirm")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentAdjustmentItem(
    bitmap: Bitmap,
    index: Int,
    offset: Int,
    onOffsetChange: (Int) -> Unit
) {
    val stepSize = 10 // Pixels per button press
    val maxOffset = 500 // Increased range

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Seg ${index + 1}",
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))

            // Thumbnail
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black)
            ) {
                if (!bitmap.isRecycled) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Segment ${index + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Offset display
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (offset != 0) Color(0xFF4CAF50).copy(alpha = 0.15f)
                       else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = when {
                        offset > 0 -> "+$offset"
                        offset < 0 -> "$offset"
                        else -> "0"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (offset != 0) Color(0xFF4CAF50)
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // +/- buttons
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalIconButton(
                    onClick = {
                        onOffsetChange((offset - stepSize).coerceIn(-maxOffset, maxOffset))
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Remove, "Shift up", Modifier.size(18.dp))
                }

                FilledTonalIconButton(
                    onClick = {
                        onOffsetChange((offset + stepSize).coerceIn(-maxOffset, maxOffset))
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Add, "Shift down", Modifier.size(18.dp))
                }
            }
        }
    }
}
