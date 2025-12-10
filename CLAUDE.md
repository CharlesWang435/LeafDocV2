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
├── stitching/       # Image stitching engine and overlap guidance
├── data/
│   ├── model/       # Room entities and data classes
│   ├── local/       # Room database, DAOs
│   ├── remote/      # Retrofit API service (Plant.id)
│   ├── repository/  # Data access layer
│   └── preferences/ # DataStore preferences
├── di/              # Hilt dependency injection modules
├── ui/
│   ├── camera/      # Camera capture screen (ViewModel + Screen)
│   ├── gallery/     # Session browser
│   ├── results/     # Diagnosis results and export
│   ├── settings/    # App preferences
│   └── theme/       # Material 3 theming
├── navigation/      # Compose Navigation graph
└── util/            # LocationManager and utilities
```

### Key Data Flow

1. **Capture Flow**: `CameraScreen` → `ProCameraController` (CameraX) → `CameraViewModel` → `ImageRepository` saves segments → `LeafSessionRepository` stores metadata in Room

2. **Stitching Flow**: `CameraViewModel.finishSession()` → `ImageStitcher.stitchImages()` → correlation-based alignment → gradient blending → saves panorama

3. **Diagnosis Flow**: `ResultsViewModel.analyzeDiagnosis()` → `DiagnosisRepository` → `DiagnosisApiService` (Plant.id API) → parses response → updates Room

### Critical Components

- **ProCameraController** (`camera/`): Wraps CameraX with Camera2 interop for manual ISO, shutter speed, focus distance, white balance control. Uses `CaptureRequestOptions` to override auto settings.

- **ImageStitcher** (`stitching/`): Horizontal stitching using normalized cross-correlation for alignment and linear gradient blending. Segments are stitched left-to-right as the leaf passes through the device.

- **OverlapGuide** (`stitching/`): Provides real-time visual guidance by showing previous segment edge overlay and calculating alignment scores.

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
