package com.leafdoc.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.leafdoc.app.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesManager(private val context: Context) {

    // Camera settings keys
    private object PreferencesKeys {
        val ISO = intPreferencesKey("camera_iso")
        val SHUTTER_SPEED = longPreferencesKey("camera_shutter_speed")
        val FOCUS_DISTANCE = floatPreferencesKey("camera_focus_distance")
        val WHITE_BALANCE = stringPreferencesKey("camera_white_balance")
        val EXPOSURE_COMPENSATION = floatPreferencesKey("camera_exposure_compensation")
        val RESOLUTION = stringPreferencesKey("camera_resolution")
        val FLASH_MODE = stringPreferencesKey("camera_flash_mode")
        val GRID_OVERLAY = stringPreferencesKey("camera_grid_overlay")
        val SHOW_HISTOGRAM = booleanPreferencesKey("camera_show_histogram")
        val SHOW_FOCUS_PEAKING = booleanPreferencesKey("camera_show_focus_peaking")
        val SHOW_ZEBRAS = booleanPreferencesKey("camera_show_zebras")
        val ZEBRA_THRESHOLD = intPreferencesKey("camera_zebra_threshold")

        // Export settings
        val EXPORT_FORMAT = stringPreferencesKey("export_format")
        val EXPORT_QUALITY = intPreferencesKey("export_quality")
        val EXPORT_INCLUDE_METADATA = booleanPreferencesKey("export_include_metadata")
        val EXPORT_LOCATION = stringPreferencesKey("export_location")

        // User info
        val FARMER_ID = stringPreferencesKey("farmer_id")
        val FIELD_ID = stringPreferencesKey("field_id")

        // App settings
        val OVERLAP_GUIDE_PERCENTAGE = intPreferencesKey("overlap_guide_percentage")
        val AUTO_SAVE_SEGMENTS = booleanPreferencesKey("auto_save_segments")
        val VIBRATE_ON_CAPTURE = booleanPreferencesKey("vibrate_on_capture")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")

        // Midrib alignment settings
        val MIDRIB_ALIGNMENT_ENABLED = booleanPreferencesKey("midrib_alignment_enabled")
        val MIDRIB_SEARCH_TOLERANCE = intPreferencesKey("midrib_search_tolerance")
        val MIDRIB_GUIDE_ENABLED = booleanPreferencesKey("midrib_guide_enabled")
        val MIDRIB_GUIDE_POSITION = floatPreferencesKey("midrib_guide_position")
        val MIDRIB_GUIDE_THICKNESS = floatPreferencesKey("midrib_guide_thickness")
        val MIDRIB_GUIDE_LOCKED = booleanPreferencesKey("midrib_guide_locked")
        val CROP_RECT_LOCKED = booleanPreferencesKey("crop_rect_locked")

        // AI diagnosis settings
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val PROMPT_TEMPLATE_ID = stringPreferencesKey("prompt_template_id")
    }

    val cameraSettings: Flow<CameraSettings> = context.dataStore.data.map { preferences ->
        CameraSettings(
            iso = preferences[PreferencesKeys.ISO] ?: CameraSettings.ISO_AUTO,
            shutterSpeed = preferences[PreferencesKeys.SHUTTER_SPEED] ?: CameraSettings.SHUTTER_AUTO,
            focusDistance = preferences[PreferencesKeys.FOCUS_DISTANCE] ?: CameraSettings.FOCUS_AUTO,
            whiteBalance = preferences[PreferencesKeys.WHITE_BALANCE]?.let {
                WhiteBalanceMode.valueOf(it)
            } ?: WhiteBalanceMode.AUTO,
            exposureCompensation = preferences[PreferencesKeys.EXPOSURE_COMPENSATION] ?: 0f,
            resolution = preferences[PreferencesKeys.RESOLUTION]?.let {
                ResolutionMode.valueOf(it)
            } ?: ResolutionMode.FULL,
            flashMode = preferences[PreferencesKeys.FLASH_MODE]?.let {
                FlashMode.valueOf(it)
            } ?: FlashMode.OFF,
            gridOverlay = preferences[PreferencesKeys.GRID_OVERLAY]?.let {
                GridOverlayType.valueOf(it)
            } ?: GridOverlayType.THIRDS,
            showHistogram = preferences[PreferencesKeys.SHOW_HISTOGRAM] ?: true,
            showFocusPeaking = preferences[PreferencesKeys.SHOW_FOCUS_PEAKING] ?: false,
            showZebras = preferences[PreferencesKeys.SHOW_ZEBRAS] ?: false,
            zebraThreshold = preferences[PreferencesKeys.ZEBRA_THRESHOLD] ?: 95
        )
    }

    val exportSettings: Flow<ExportSettings> = context.dataStore.data.map { preferences ->
        ExportSettings(
            format = preferences[PreferencesKeys.EXPORT_FORMAT]?.let {
                ImageFormat.valueOf(it)
            } ?: ImageFormat.JPEG,
            quality = preferences[PreferencesKeys.EXPORT_QUALITY] ?: 95,
            includeMetadata = preferences[PreferencesKeys.EXPORT_INCLUDE_METADATA] ?: true,
            exportLocation = preferences[PreferencesKeys.EXPORT_LOCATION]?.let {
                ExportLocation.valueOf(it)
            } ?: ExportLocation.PICTURES_FOLDER
        )
    }

    val farmerId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.FARMER_ID] ?: ""
    }

    val fieldId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.FIELD_ID] ?: ""
    }

    val overlapGuidePercentage: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.OVERLAP_GUIDE_PERCENTAGE] ?: 10
    }

    val autoSaveSegments: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.AUTO_SAVE_SEGMENTS] ?: true
    }

    val vibrateOnCapture: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.VIBRATE_ON_CAPTURE] ?: true
    }

    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.KEEP_SCREEN_ON] ?: true
    }

    val midribAlignmentEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MIDRIB_ALIGNMENT_ENABLED] ?: true
    }

    val midribSearchTolerance: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MIDRIB_SEARCH_TOLERANCE] ?: 50 // 50% of image height
    }

    val midribGuideEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MIDRIB_GUIDE_ENABLED] ?: true
    }

    val midribGuidePosition: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MIDRIB_GUIDE_POSITION] ?: 0.5f // Center
    }

    val midribGuideThickness: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MIDRIB_GUIDE_THICKNESS] ?: 0.05f // 5% of screen height
    }

    val midribGuideLocked: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MIDRIB_GUIDE_LOCKED] ?: false
    }

    val cropRectLocked: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.CROP_RECT_LOCKED] ?: false
    }

    val aiProvider: Flow<AiProviderType> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.AI_PROVIDER]?.let {
            try {
                AiProviderType.valueOf(it)
            } catch (e: IllegalArgumentException) {
                AiProviderType.GEMINI  // Default fallback
            }
        } ?: AiProviderType.GEMINI  // Default to Gemini
    }

    val promptTemplateId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PROMPT_TEMPLATE_ID] ?: "standard_analysis"  // Default to standard analysis
    }

    suspend fun updateCameraSettings(settings: CameraSettings) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ISO] = settings.iso
            preferences[PreferencesKeys.SHUTTER_SPEED] = settings.shutterSpeed
            preferences[PreferencesKeys.FOCUS_DISTANCE] = settings.focusDistance
            preferences[PreferencesKeys.WHITE_BALANCE] = settings.whiteBalance.name
            preferences[PreferencesKeys.EXPOSURE_COMPENSATION] = settings.exposureCompensation
            preferences[PreferencesKeys.RESOLUTION] = settings.resolution.name
            preferences[PreferencesKeys.FLASH_MODE] = settings.flashMode.name
            preferences[PreferencesKeys.GRID_OVERLAY] = settings.gridOverlay.name
            preferences[PreferencesKeys.SHOW_HISTOGRAM] = settings.showHistogram
            preferences[PreferencesKeys.SHOW_FOCUS_PEAKING] = settings.showFocusPeaking
            preferences[PreferencesKeys.SHOW_ZEBRAS] = settings.showZebras
            preferences[PreferencesKeys.ZEBRA_THRESHOLD] = settings.zebraThreshold
        }
    }

    suspend fun updateExportSettings(settings: ExportSettings) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.EXPORT_FORMAT] = settings.format.name
            preferences[PreferencesKeys.EXPORT_QUALITY] = settings.quality
            preferences[PreferencesKeys.EXPORT_INCLUDE_METADATA] = settings.includeMetadata
            preferences[PreferencesKeys.EXPORT_LOCATION] = settings.exportLocation.name
        }
    }

    suspend fun updateFarmerId(farmerId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FARMER_ID] = farmerId
        }
    }

    suspend fun updateFieldId(fieldId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FIELD_ID] = fieldId
        }
    }

    suspend fun updateOverlapGuidePercentage(percentage: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.OVERLAP_GUIDE_PERCENTAGE] = percentage.coerceIn(5, 25)
        }
    }

    suspend fun updateAutoSaveSegments(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_SAVE_SEGMENTS] = enabled
        }
    }

    suspend fun updateVibrateOnCapture(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VIBRATE_ON_CAPTURE] = enabled
        }
    }

    suspend fun updateKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEEP_SCREEN_ON] = enabled
        }
    }

    suspend fun updateMidribAlignmentEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIDRIB_ALIGNMENT_ENABLED] = enabled
        }
    }

    suspend fun updateMidribSearchTolerance(percentage: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIDRIB_SEARCH_TOLERANCE] = percentage.coerceIn(20, 80)
        }
    }

    suspend fun updateMidribGuideEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIDRIB_GUIDE_ENABLED] = enabled
        }
    }

    suspend fun updateMidribGuidePosition(position: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIDRIB_GUIDE_POSITION] = position.coerceIn(0f, 1f)
        }
    }

    suspend fun updateMidribGuideThickness(thickness: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIDRIB_GUIDE_THICKNESS] = thickness.coerceIn(0.02f, 0.15f)
        }
    }

    suspend fun updateMidribGuideLocked(locked: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIDRIB_GUIDE_LOCKED] = locked
        }
    }

    suspend fun updateCropRectLocked(locked: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CROP_RECT_LOCKED] = locked
        }
    }

    suspend fun updateAiProvider(provider: AiProviderType) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AI_PROVIDER] = provider.name
        }
    }

    suspend fun updatePromptTemplateId(templateId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PROMPT_TEMPLATE_ID] = templateId
        }
    }
}
