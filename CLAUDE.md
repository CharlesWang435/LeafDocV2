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
│   ├── remote/      # Retrofit API service (Plant.id)
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

2. **Stitching Flow**: `CameraViewModel.finishSession()` → `SimpleStitcher.stitchImages()` → (optional) `MidribAligner` aligns segments vertically → left-to-right concatenation with gradient blending → saves panorama

3. **Diagnosis Flow**: `ResultsViewModel.analyzeDiagnosis()` → `DiagnosisRepository` → `DiagnosisApiService` (Plant.id API) → parses response → updates Room

### Critical Components

- **ProCameraController** (`camera/`): Wraps CameraX with Camera2 interop for manual ISO, shutter speed, focus distance, white balance control. Uses `CaptureRequestOptions` to override auto settings.

- **SimpleStitcher** (`stitching/`): Horizontal concatenation with configurable overlap blending. Segments are stitched left-to-right (base to tip) with linear gradient blending. Optionally uses `MidribAligner` to correct vertical drift between segments.

- **MidribAligner** (`stitching/`): Detects corn leaf midrib (central vein) using green channel dominance analysis. In transmittance imaging, the midrib has distinct green channel characteristics. Uses sliding window to find the horizontal band with highest green dominance ratio (`green / (red + green + blue)`). Calculates vertical offsets to align all segments to the same Y position, correcting for hand movement during capture.

- **CropRectangleOverlay** (`ui/camera/`): Draggable/resizable crop rectangle overlay. Users define the capture region to match their light board. Supports lock/unlock to freeze position. Captured images are cropped to this region before stitching.

- **MidribGuideOverlay** (`ui/camera/`): Horizontal guide band with light green gradient appearance to help users align the leaf midrib during capture. Supports drag-to-move and edge handles for adjustable thickness. Uses local state during drag operations for smooth gesture handling. Position and thickness persist via DataStore preferences.

- **Crop Coordinate Mapping**: The camera preview uses `FILL_CENTER` scaling, which may crop the image differently than the preview shows. `CameraViewModel.cropBitmap()` calculates the visible region based on aspect ratio differences between the captured image and preview, then maps the crop rectangle coordinates accordingly for accurate cropping.

### Database Schema

Two Room entities with a one-to-many relationship:
- `LeafSession`: Captures metadata (farmer ID, field ID, GPS, diagnosis status, stitched image path)
- `LeafSegment`: Individual captured frames with camera EXIF data, linked to session via `sessionId`

### DI Modules

- `DatabaseModule`: Provides Room database and DAOs (singleton)
- `NetworkModule`: Provides OkHttp client with API key interceptor and Retrofit
- `AppModule`: Provides repositories, preferences manager, location manager

## Configuration

The Plant.id API key is loaded from `local.properties` (not committed to source control):
```properties
PLANT_ID_API_KEY=your_api_key_here
```

Copy `local.properties.example` to `local.properties` and add your key. The build script in `app/build.gradle.kts` reads this file automatically.

## Logging

Uses Timber for structured logging. In debug builds, full logging is enabled. In release builds, only WARN and ERROR levels are logged. Initialize is handled in `LeafDocApplication`.

```kotlin
Timber.d("Debug message")
Timber.e(exception, "Error occurred")
```

## Tech Stack

- Kotlin 2.0.21, Compose BOM 2024.11.00
- CameraX 1.4.0 with Camera2 interop (`@ExperimentalCamera2Interop`)
- Room 2.6.1, Hilt 2.52, Retrofit 2.11.0, Timber 5.0.1
- Target SDK 35, Min SDK 26
- Gradle 8.9

## Database Migrations

Room migrations are defined in `LeafDocDatabase.ALL_MIGRATIONS`. When modifying the schema:
1. Increment the database version
2. Add a migration object to the companion object
3. Add it to the `ALL_MIGRATIONS` array

The database is configured in `DatabaseModule` with `.addMigrations(*LeafDocDatabase.ALL_MIGRATIONS)`.

## Stitching Algorithm

The stitching system has two main components:

1. **SimpleStitcher**: Concatenates segments left-to-right with configurable overlap percentage (default 10%). Uses linear gradient blending in overlap regions for smooth transitions.

2. **MidribAligner** (optional, enabled by default):
   - Uses green channel dominance analysis to detect the midrib (central vein)
   - Calculates green ratio `green / (red + green + blue)` for each pixel
   - Uses sliding window to find the horizontal band with highest green dominance
   - Calculates vertical offset needed to align each segment's midrib to a reference position
   - Creates shifted bitmaps before stitching to eliminate "staircase" effect from hand movement

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

Horizontal panel in top bar for locking overlays:
- Crop rectangle lock toggle
- Midrib guide lock toggle
- Expands/collapses with arrow button
