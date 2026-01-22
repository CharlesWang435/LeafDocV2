# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug

# Run all tests
./gradlew test

# Run single test class
./gradlew test --tests "com.leafdoc.app.ExampleTest"

# Clean build
./gradlew clean

# Check dependencies
./gradlew dependencies
```

## Architecture Overview

LeafDoc is an Android app for capturing and stitching corn leaf images using transmittance imaging, with cloud AI diagnosis. The app follows **MVVM architecture** with **Jetpack Compose UI**.

### Layer Structure

```
com.leafdoc.app/
├── camera/          # CameraX + Camera2 interop for pro manual controls
├── stitching/       # Image stitching engine with midrib alignment
├── data/
│   ├── model/       # Room entities and data classes
│   ├── local/       # Room database, DAOs
│   ├── remote/      # GeminiAiService for AI diagnosis
│   ├── repository/  # Data access layer
│   └── preferences/ # DataStore preferences
├── di/              # Hilt dependency injection modules
├── ui/
│   ├── camera/      # Camera capture screen, crop overlay, midrib guide
│   ├── gallery/     # Session browser
│   ├── results/     # Diagnosis results and export
│   ├── settings/    # App preferences
│   └── theme/       # Material 3 theming
├── navigation/      # Compose Navigation graph
└── util/            # LocationManager and utilities
```

### Key Data Flow

1. **Capture Flow**: `CameraScreen` → `ProCameraController` (CameraX) → `CameraViewModel` → crops image to `CropRect` → `ImageRepository` saves segments → `LeafSessionRepository` stores metadata in Room

2. **Stitching Flow**: `CameraViewModel.finishSession()` → (if multiple segments) `ManualAlignmentScreen` for Y-axis adjustment → `SimpleStitcher.stitchImages()` with manual offsets → left-to-right concatenation with gradient blending → saves panorama. Single-segment captures skip the alignment screen.

3. **Diagnosis Flow**: `ResultsViewModel.analyzeDiagnosis()` → `DiagnosisRepository` → `GeminiAiService` (Google Gemini API) → parses response → updates Room

4. **Export Flow**: `ResultsScreen` → User selects export mode (Stitched Only / Frames Only / Both) → `ResultsViewModel.exportWithMode()` → `ImageRepository.exportAllSegments()` with progress callbacks → exports to MediaStore → displays share option for multiple images

5. **Viewing Flow**: `GalleryScreen` (grid view) → tap image → `ZoomableImageDialog` (fullscreen preview with pinch-to-zoom) → "View Results & Diagnosis" button → `ResultsScreen` (stitched image at top, individual frames directly underneath with zoom support, followed by diagnosis details and export options). Individual frames are displayed in `IndividualFramesCard` with `ClickableZoomableImage` thumbnails that support pinch-to-zoom and double-tap-to-zoom gestures for detailed frame inspection.

### Critical Components

- **ProCameraController** (`camera/`): Wraps CameraX with Camera2 interop for manual ISO, shutter speed, focus distance, white balance control. Uses `CaptureRequestOptions` to override auto settings. **Multi-Lens Support**: Automatically detects all available back-facing camera lenses (ultra-wide, wide, telephoto, macro) and provides `switchLens()` function for runtime lens switching. Lens types are determined by focal length and minimum focus distance characteristics.

- **SimpleStitcher** (`stitching/`): Horizontal concatenation with configurable overlap blending. Segments are stitched left-to-right (base to tip) with linear gradient blending. Optionally uses `MidribAligner` to correct vertical drift between segments.

- **MidribAligner** (`stitching/`): Detects corn leaf midrib (central vein) using green channel dominance analysis. In transmittance imaging, the midrib has distinct green channel characteristics. Uses sliding window to find the horizontal band with highest green dominance ratio (`green / (red + green + blue)`). Calculates vertical offsets to align all segments to the same Y position, correcting for hand movement during capture.

- **CropRectangleOverlay** (`ui/camera/`): Draggable/resizable crop rectangle overlay. Users define the capture region to match their light board. Supports lock/unlock to freeze position and enable/disable toggle to capture full frame without cropping. Captured images are cropped to this region before stitching (when enabled).

- **MidribGuideOverlay** (`ui/camera/`): Horizontal guide band with light green gradient appearance to help users align the leaf midrib during capture. Supports drag-to-move and edge handles for adjustable thickness. Uses local state during drag operations for smooth gesture handling. Position and thickness persist via DataStore preferences.

- **Crop Coordinate Mapping**: The camera preview uses `FILL_CENTER` scaling, which displays a portion of the full captured image to fill the screen. The captured image itself is always the full sensor output (using `HIGHEST_AVAILABLE_STRATEGY` resolution selector). When crop rectangle is **enabled**, `CameraViewModel.cropBitmap()` calculates the visible region based on aspect ratio differences between the captured image and preview, then maps the crop rectangle coordinates accordingly for accurate cropping. When crop rectangle is **disabled**, the full captured image is saved without any cropping.

- **ManualAlignmentScreen** (`ui/camera/`): Fullscreen dialog for manual Y-axis alignment before stitching. Shows live preview synchronized with segment thumbnails, +/- buttons to adjust each segment's vertical offset (±500px range), and "Auto Align" button that applies `MidribAligner` detection as a starting point. Available both during capture flow and from `ResultsScreen` for re-alignment of saved sessions. Segment thumbnails are clickable to open `FullscreenSegmentViewer` with horizontal swipe navigation between frames.

- **Image Quality**: Camera captures at device's maximum native resolution using `ResolutionSelector` with `HIGHEST_AVAILABLE_STRATEGY`, ensuring the full sensor output is captured without aspect ratio cropping. JPEG quality is set to 100 for maximum detail. This ensures high-quality scientific imaging suitable for disease analysis. The captured images are always the full sensor output, regardless of what's visible in the preview (which uses `FILL_CENTER` scaling).

### Database Schema

Two Room entities with a one-to-many relationship:
- `LeafSession`: Captures metadata (farmer ID, field ID, GPS, diagnosis status, stitched image path)
- `LeafSegment`: Individual captured frames with camera EXIF data, linked to session via `sessionId`. Includes `frameLabel` field for individual frame exports (e.g., "Frame 3")

**Current database version: 2** (see `LeafDocDatabase.kt`)

Database migrations are in `LeafDocDatabase.ALL_MIGRATIONS`. The most recent migration (v1→v2) adds the `frameLabel` column for individual frame export functionality.

### DiagnosisDisplay Model

Key fields for displaying AI analysis results:
- `isHealthy`: Boolean health assessment
- `healthScore`: Estimated health indicator (0-100) - not a definitive diagnosis
- `leafDescription`: Detailed AI-generated visual description of the leaf (color, lesions, midrib condition, tissue health)
- `primaryDiagnosis`: Main disease name or "Healthy"
- `confidence`: Analysis confidence percentage (0-100)
- `diseases`: List of `DiseaseInfo` with name, probability, severity, treatments
- `suggestions`: Actionable recommendations

### DI Modules

- `DatabaseModule`: Provides Room database and DAOs (singleton)
- `NetworkModule`: Provides Gson, OkHttpClient (30s connect, 60s read/write timeouts)
- `AiModule`: Provides AI providers (Gemini, Claude, ChatGPT) and AiProviderFactory
- `AppModule`: Provides repositories, preferences manager, location manager

## Configuration

API keys are loaded from `local.properties` (not committed to source control):
```properties
GEMINI_API_KEY=your_gemini_key_here
CLAUDE_API_KEY=your_anthropic_key_here
CHATGPT_API_KEY=your_openai_key_here
```

Get API keys from:
- [Google AI Studio](https://aistudio.google.com/apikey) for Gemini
- [Anthropic Console](https://console.anthropic.com/) for Claude
- [OpenAI Platform](https://platform.openai.com/) for ChatGPT

Copy `local.properties.example` to `local.properties` and add your keys. The build script reads this file automatically. At least one provider must be configured.

## AI Diagnosis System

### Multi-Provider Architecture

The app supports three AI providers via the `AiProvider` interface:
- **GeminiAiProvider**: Google Gemini 2.5 Flash (default, recommended)
- **ClaudeAiProvider**: Anthropic Claude 3.5 Sonnet
- **ChatGptAiProvider**: OpenAI GPT-4o

`AiProviderFactory` manages provider instances and checks configuration status. `DiagnosisRepository.analyzeLeaf()` accepts optional `overrideProvider` and `overridePromptId` parameters for reanalysis with different models.

### Prompt Templates

`PromptLibrary` contains four predefined prompt templates optimized for corn diagnosis:
- **quick_check**: Fast preliminary screening (~5s, 1024 tokens)
- **standard_analysis**: Balanced detail with treatments (~15s, 2048 tokens)
- **detailed_diagnosis**: Comprehensive pathology report (~30s, 4096 tokens)
- **research_mode**: Maximum detail for research/academic use (~30s, 4096 tokens)

Templates use `PromptTemplate.buildPrompt()` to inject location context and imaging method. All templates share a common JSON output format and corn disease database.

### Reanalysis Feature

`ResultsViewModel.reanalyzeDiagnosis(provider, promptId)` allows rerunning analysis with different AI models and prompt templates. `ResultsScreen` shows a `ReanalyzeDialog` for model/prompt selection.

### Supported Diseases

Prompts are specialized for corn diseases: Northern Corn Leaf Blight, Gray Leaf Spot, Southern Corn Leaf Blight, Common Rust, Anthracnose, Goss's Wilt, Eyespot, Holcus Spot, Tar Spot, Diplodia Leaf Streak, Physoderma Brown Spot (plus additional pathogens in research mode)

## Logging

Uses Timber for structured logging. In debug builds, full logging is enabled. In release builds, only WARN and ERROR levels are logged. Initialize is handled in `LeafDocApplication`.

```kotlin
Timber.d("Debug message")
Timber.e(exception, "Error occurred")
```

## Tech Stack

- Kotlin 2.0.21, Compose BOM 2024.11.00
- CameraX 1.4.0 with Camera2 interop (`@ExperimentalCamera2Interop`)
- Room 2.6.1, Hilt 2.52, Timber 5.0.1
- Google Generative AI SDK 0.9.0 (Gemini 2.5 Flash for disease diagnosis)
- Target SDK 35, Min SDK 26
- Gradle 8.9

## Export System

The app supports three export modes via `ExportMode` enum:
- **STITCHED_ONLY**: Exports only the stitched panorama
- **FRAMES_ONLY**: Exports individual captured frames with frame labels
- **BOTH**: Exports both stitched image and all individual frames

### Frame Labeling Strategy

Frame labels are generated during capture:
- First frame: `frameLabel = null` (might be single-frame session)
- Subsequent frames: `frameLabel = "Frame 2"`, `"Frame 3"`, etc.
- Single-frame sessions: `frameLabel` cleared to NULL in `CameraViewModel.finishWithSingleSegment()`

File naming format: `LeafDoc_{FarmerId}_{FieldId}_Leaf{N}_{FrameLabel}_{timestamp}.{ext}`

Export progress is tracked with callbacks (`onProgress: (current, total) -> Unit`) and displayed in UI with `LinearProgressIndicator`.

## Database Migrations

Room migrations are defined in `LeafDocDatabase.ALL_MIGRATIONS`. When modifying the schema:
1. Increment the database version in `@Database` annotation
2. Create a migration object in the companion object (e.g., `MIGRATION_X_Y`)
3. Add it to the `ALL_MIGRATIONS` array
4. Use `database.execSQL()` for schema changes

The database is configured in `DatabaseModule` with `.addMigrations(*LeafDocDatabase.ALL_MIGRATIONS)`.

**Example migration** (v1→v2):
```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE leaf_segments ADD COLUMN frameLabel TEXT DEFAULT NULL")
    }
}
```

## Stitching Algorithm

The stitching system has two main components:

1. **SimpleStitcher**: Concatenates segments left-to-right with configurable overlap percentage (default 10%). Uses linear gradient blending in overlap regions for smooth transitions.

2. **MidribAligner** (used by ManualAlignmentScreen's "Auto Align"):
   - Uses green channel dominance analysis to detect the midrib (central vein)
   - Calculates green ratio `green / (red + green + blue)` for each pixel
   - Uses sliding window to find the horizontal band with highest green dominance
   - `detectOffsets()` returns Y offsets without creating new bitmaps (for UI preview)
   - Eliminates "staircase" effect from hand movement during capture

Settings controlled in `UserPreferencesManager`:
- `midribAlignmentEnabled`: Toggle auto-alignment on/off
- `midribSearchTolerance`: How much of image height to search (20-80%)
- `overlapGuidePercentage`: Blend overlap between segments (5-25%)
- `midribGuidePosition`: Vertical position of the guide (0-1 range)
- `midribGuideThickness`: Thickness of the guide band (2-15% of screen height)

## UI Components

### Camera Screen Layout

The camera screen features a compact, professional design:

- **Top Bar**: Settings button, expandable lock controls panel (horizontal), gallery button
- **Center**: Camera preview with crop rectangle overlay and midrib guide
- **Bottom**: Compact horizontal pro controls strip with icon-based buttons

### Pro Controls Panel

A compact horizontal strip at the bottom featuring:
- ISO control with quick select options (Auto, 100, 200, 400, 800)
- Shutter speed with horizontal scrolling options
- Focus control with Auto/Manual toggle
- White balance selector
- Exposure compensation slider

Controls expand inline when tapped, minimizing screen obstruction.

### Lock Controls Panel

Horizontal panel in top bar for managing overlays:
- Crop rectangle enable/disable toggle
- Crop rectangle lock toggle (only shown when crop is enabled)
- Midrib guide visibility toggle
- Midrib guide lock toggle (only shown when guide is enabled)
- Midrib auto-alignment toggle
- Expands/collapses with arrow button

### Lens Selector

Dropdown menu in top bar for switching camera lenses:
- Automatically detects all available back-facing lenses
- Displays lens type icons (Ultra Wide, Wide, Telephoto, Macro)
- Only shown when device has multiple camera lenses
- Current lens highlighted in cyan
- Supports runtime lens switching without restarting camera

### Fullscreen Viewers

**FullscreenSegmentViewer** (in ManualAlignmentScreen):
- View bitmaps in memory during alignment
- Horizontal pager for swipe navigation
- Page indicators at bottom
- Used for inspecting segments before stitching

**ResultsScreen Individual Frames**:
- Individual frames displayed in horizontal scrolling row directly under stitched image
- Each thumbnail uses `ClickableZoomableImage` for tap-to-zoom with pinch and double-tap gestures
- Thumbnails show frame labels and zoom hint icon
- Larger 120dp thumbnails for better preview
- Fullscreen zoom viewer opens with pinch-to-zoom, drag-to-pan, and double-tap-to-zoom support

The FullscreenSegmentViewer in ManualAlignmentScreen uses `androidx.compose.foundation.pager.HorizontalPager` with `@OptIn(ExperimentalFoundationApi::class)` and displays against black background for professional image inspection.
