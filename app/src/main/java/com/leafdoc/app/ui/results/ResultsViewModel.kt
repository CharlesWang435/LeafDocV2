package com.leafdoc.app.ui.results

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leafdoc.app.data.model.*
import com.leafdoc.app.data.preferences.UserPreferencesManager
import com.leafdoc.app.data.remote.ai.AiProviderFactory
import com.leafdoc.app.data.remote.ai.prompts.PromptLibrary
import com.leafdoc.app.data.remote.ai.prompts.PromptTemplateInfoFactory
import com.leafdoc.app.data.repository.DiagnosisRepository
import com.leafdoc.app.data.repository.ImageRepository
import com.leafdoc.app.data.repository.LeafSessionRepository
import com.leafdoc.app.stitching.MidribAligner
import com.leafdoc.app.stitching.SimpleStitcher
import com.leafdoc.app.stitching.StitchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ResultsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: LeafSessionRepository,
    private val imageRepository: ImageRepository,
    private val diagnosisRepository: DiagnosisRepository,
    private val preferencesManager: UserPreferencesManager,
    private val aiProviderFactory: AiProviderFactory
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>("sessionId") ?: ""

    val session: StateFlow<LeafSession?> = sessionRepository.getSessionByIdFlow(sessionId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val segments = sessionRepository.getSegmentsBySession(sessionId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val exportSettings = preferencesManager.exportSettings
        .stateIn(viewModelScope, SharingStarted.Lazily, ExportSettings())

    private val _uiState = MutableStateFlow(ResultsUiState())
    val uiState: StateFlow<ResultsUiState> = _uiState.asStateFlow()

    private val _diagnosis = MutableStateFlow<DiagnosisDisplay?>(null)
    val diagnosis: StateFlow<DiagnosisDisplay?> = _diagnosis.asStateFlow()

    // Available AI providers and prompts for reanalysis
    val availableProviders: List<Pair<AiProviderType, Boolean>>
        get() = AiProviderType.entries.map { it to aiProviderFactory.isProviderConfigured(it) }

    val availablePrompts: List<PromptTemplateInfo>
        get() = PromptTemplateInfoFactory.getAllTemplateInfos()

    // Alignment editing state
    private val simpleStitcher = SimpleStitcher()
    private val midribAligner = MidribAligner()

    private val _showAlignmentScreen = MutableStateFlow(false)
    val showAlignmentScreen: StateFlow<Boolean> = _showAlignmentScreen.asStateFlow()

    private val _alignmentBitmaps = MutableStateFlow<List<Bitmap>>(emptyList())
    val alignmentBitmaps: StateFlow<List<Bitmap>> = _alignmentBitmaps.asStateFlow()

    val overlapPercentage: StateFlow<Int> = preferencesManager.overlapGuidePercentage
        .stateIn(viewModelScope, SharingStarted.Eagerly, 10)

    val midribSearchTolerance: StateFlow<Int> = preferencesManager.midribSearchTolerance
        .stateIn(viewModelScope, SharingStarted.Eagerly, 50)

    init {
        loadDiagnosis()
    }

    private fun loadDiagnosis() {
        viewModelScope.launch {
            session.collect { sess ->
                if (sess != null && sess.diagnosisStatus == DiagnosisStatus.COMPLETED) {
                    _diagnosis.value = diagnosisRepository.parseSavedDiagnosis(
                        sessionId,
                        sess.diagnosisResult
                    )
                }
            }
        }
    }

    fun analyzeDiagnosis() {
        viewModelScope.launch {
            val sess = session.value ?: return@launch
            val imagePath = sess.stitchedImagePath ?: return@launch

            _uiState.update { it.copy(isAnalyzing = true, error = null) }

            val result = diagnosisRepository.analyzeLeaf(
                sessionId = sessionId,
                imagePath = imagePath,
                latitude = sess.latitude,
                longitude = sess.longitude
            )

            result.fold(
                onSuccess = { diagnosis ->
                    _diagnosis.value = diagnosis
                    _uiState.update { it.copy(
                        isAnalyzing = false,
                        message = "Analysis complete"
                    )}
                },
                onFailure = { error ->
                    _uiState.update { it.copy(
                        isAnalyzing = false,
                        error = "Analysis failed: ${error.message}"
                    )}
                }
            )
        }
    }

    /**
     * Reanalyze the leaf with a specific AI model and prompt template.
     */
    fun reanalyzeDiagnosis(provider: AiProviderType, promptId: String) {
        viewModelScope.launch {
            val sess = session.value ?: return@launch
            val imagePath = sess.stitchedImagePath ?: return@launch

            // Check if provider is configured
            if (!aiProviderFactory.isProviderConfigured(provider)) {
                _uiState.update { it.copy(
                    error = "${provider.displayName} is not configured. Please add the API key in settings."
                )}
                return@launch
            }

            _uiState.update { it.copy(isAnalyzing = true, error = null) }

            val result = diagnosisRepository.analyzeLeaf(
                sessionId = sessionId,
                imagePath = imagePath,
                latitude = sess.latitude,
                longitude = sess.longitude,
                overrideProvider = provider,
                overridePromptId = promptId
            )

            result.fold(
                onSuccess = { diagnosis ->
                    _diagnosis.value = diagnosis
                    _uiState.update { it.copy(
                        isAnalyzing = false,
                        message = "Reanalysis complete using ${provider.displayName}"
                    )}
                },
                onFailure = { error ->
                    _uiState.update { it.copy(
                        isAnalyzing = false,
                        error = "Reanalysis failed: ${error.message}"
                    )}
                }
            )
        }
    }

    fun exportImage(format: ImageFormat? = null) {
        viewModelScope.launch {
            val sess = session.value ?: return@launch
            val imagePath = sess.stitchedImagePath ?: return@launch

            _uiState.update { it.copy(isExporting = true, error = null) }

            try {
                val settings = if (format != null) {
                    exportSettings.value.copy(format = format)
                } else {
                    exportSettings.value
                }

                val fileName = buildExportFileName(sess, settings.format)
                val uri = imageRepository.exportImage(imagePath, settings, fileName)

                if (uri != null) {
                    _uiState.update { it.copy(
                        isExporting = false,
                        exportedUri = uri,
                        message = "Image exported successfully"
                    )}
                } else {
                    _uiState.update { it.copy(
                        isExporting = false,
                        error = "Failed to export image"
                    )}
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isExporting = false,
                    error = "Export failed: ${e.message}"
                )}
            }
        }
    }

    private fun buildExportFileName(session: LeafSession, format: ImageFormat): String {
        val parts = mutableListOf<String>()
        parts.add("LeafDoc")

        if (session.farmerId.isNotEmpty()) {
            parts.add(session.farmerId.take(20))
        }
        if (session.fieldId.isNotEmpty()) {
            parts.add(session.fieldId.take(20))
        }
        parts.add("Leaf${session.leafNumber}")

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date(session.createdAt))
        parts.add(timestamp)

        return "${parts.joinToString("_")}.${format.extension}"
    }

    fun shareImage() {
        viewModelScope.launch {
            val sess = session.value ?: return@launch
            val imagePath = sess.stitchedImagePath ?: return@launch

            try {
                val uri = imageRepository.getImageUri(imagePath)
                _uiState.update { it.copy(shareUri = uri) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to share: ${e.message}") }
            }
        }
    }

    fun updateExportFormat(format: ImageFormat) {
        viewModelScope.launch {
            preferencesManager.updateExportSettings(
                exportSettings.value.copy(format = format)
            )
        }
    }

    fun updateExportQuality(quality: Int) {
        viewModelScope.launch {
            preferencesManager.updateExportSettings(
                exportSettings.value.copy(quality = quality)
            )
        }
    }

    fun deleteSession() {
        viewModelScope.launch {
            try {
                sessionRepository.deleteSession(sessionId)
                imageRepository.deleteSessionImages(sessionId)
                _uiState.update { it.copy(sessionDeleted = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete: ${e.message}") }
            }
        }
    }

    fun updateNotes(notes: String) {
        viewModelScope.launch {
            session.value?.let { sess ->
                sessionRepository.updateSession(sess.copy(notes = notes))
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearShareUri() {
        _uiState.update { it.copy(shareUri = null) }
    }

    fun clearExportedUri() {
        _uiState.update { it.copy(exportedUri = null) }
    }

    // ==================== Alignment Editing Functions ====================

    /**
     * Loads segment images and shows the alignment screen for editing.
     */
    fun prepareForAlignment() {
        viewModelScope.launch {
            val currentSegments = segments.value
            if (currentSegments.isEmpty()) {
                _uiState.update { it.copy(error = "No segments found for this session") }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true) }

            try {
                // Load all segment bitmaps
                val bitmaps = currentSegments
                    .sortedBy { it.segmentIndex }
                    .mapNotNull { segment ->
                        imageRepository.loadBitmap(segment.imagePath)
                    }

                if (bitmaps.isEmpty()) {
                    throw Exception("Failed to load segment images")
                }

                _alignmentBitmaps.value = bitmaps
                _uiState.update { it.copy(isLoading = false) }
                _showAlignmentScreen.value = true

            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isLoading = false,
                    error = "Failed to load segments: ${e.message}"
                )}
            }
        }
    }

    /**
     * Gets auto-detected Y offsets using midrib alignment.
     */
    suspend fun getAutoAlignOffsets(): List<Int> {
        val bitmaps = _alignmentBitmaps.value
        if (bitmaps.isEmpty()) return emptyList()

        val searchTolerance = midribSearchTolerance.value / 100f
        return midribAligner.detectOffsets(bitmaps, searchTolerance)
    }

    /**
     * Generates a preview bitmap with the given manual offsets.
     */
    suspend fun generatePreview(offsets: List<Int>): Bitmap? {
        val bitmaps = _alignmentBitmaps.value
        if (bitmaps.isEmpty()) return null

        val overlapPercent = overlapPercentage.value / 100f
        return simpleStitcher.createPreview(
            images = bitmaps,
            offsets = offsets,
            overlapPercent = overlapPercent,
            scale = 0.3f
        )
    }

    /**
     * Confirms alignment with manual offsets, re-stitches, and updates the saved image.
     */
    fun confirmAlignment(offsets: List<Int>) {
        viewModelScope.launch {
            val sess = session.value ?: return@launch
            val bitmaps = _alignmentBitmaps.value

            if (bitmaps.isEmpty()) {
                _uiState.update { it.copy(error = "No images to stitch") }
                return@launch
            }

            _showAlignmentScreen.value = false
            _uiState.update { it.copy(isLoading = true, message = "Re-stitching image...") }

            try {
                // Stitch images with manual offsets
                val overlapPercent = overlapPercentage.value / 100f
                val stitchResult = simpleStitcher.stitchImages(
                    images = bitmaps,
                    overlapPercent = overlapPercent,
                    alignMidrib = false,
                    manualOffsets = offsets
                )

                // Clear alignment bitmaps
                clearAlignmentBitmaps()

                when (stitchResult) {
                    is StitchResult.Success -> {
                        // Delete old stitched image if it exists
                        sess.stitchedImagePath?.let { oldPath ->
                            try {
                                imageRepository.deleteImage(oldPath)
                            } catch (_: Exception) {
                                // Ignore deletion errors
                            }
                        }

                        // Save new stitched image
                        val newStitchedPath = imageRepository.saveStitchedImage(
                            bitmap = stitchResult.bitmap,
                            sessionId = sessionId
                        )

                        // Update session with new stitched image path
                        sessionRepository.updateSession(
                            sess.copy(stitchedImagePath = newStitchedPath)
                        )

                        _uiState.update { it.copy(
                            isLoading = false,
                            message = "Image alignment updated successfully!"
                        )}

                        stitchResult.bitmap.recycle()
                    }
                    is StitchResult.Error -> {
                        _uiState.update { it.copy(
                            isLoading = false,
                            error = "Stitching failed: ${stitchResult.message}"
                        )}
                    }
                    is StitchResult.Progress -> {
                        // Handle progress if needed
                    }
                }
            } catch (e: Exception) {
                clearAlignmentBitmaps()
                _uiState.update { it.copy(
                    isLoading = false,
                    error = "Failed to update alignment: ${e.message}"
                )}
            }
        }
    }

    /**
     * Cancels the alignment process.
     */
    fun cancelAlignment() {
        _showAlignmentScreen.value = false
        clearAlignmentBitmaps()
    }

    /**
     * Clears and recycles alignment bitmaps to free memory.
     */
    private fun clearAlignmentBitmaps() {
        _alignmentBitmaps.value.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        _alignmentBitmaps.value = emptyList()
    }
}

data class ResultsUiState(
    val isAnalyzing: Boolean = false,
    val isExporting: Boolean = false,
    val isLoading: Boolean = false,
    val exportedUri: Uri? = null,
    val shareUri: Uri? = null,
    val sessionDeleted: Boolean = false,
    val message: String? = null,
    val error: String? = null
)
