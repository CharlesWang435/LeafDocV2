# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Build + install on a connected device
./gradlew assembleRelease        # Build release APK (minify + shrink on)
./gradlew test                   # Run JVM unit tests
./gradlew test --tests "com.leafdoc.app.ExampleTest"   # Single test class
./gradlew clean
```

**Environment gotchas (important):**
- Requires **JDK 17**. If `./gradlew` fails with "Unable to locate a Java Runtime", set `JAVA_HOME` to a JDK 17 — Android Studio's bundled JBR works (`.../Android Studio.app/Contents/jbr/Contents/Home` on macOS). There may be no JDK on `PATH`.
- This project originated on Windows; the Unix `gradlew` script was once missing and has been regenerated. Both `gradlew` and `gradlew.bat` are committed.
- `installDebug` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` if a build signed by a **different debug keystore** is already on the device. Fix: `adb uninstall com.leafdoc.app` (wipes in-app sessions, not exported gallery files), then reinstall.
- API keys live in `local.properties` (git-ignored) and are exposed via `BuildConfig` — see [Configuration](#configuration). The app builds and runs without them; only AI diagnosis needs them.

## What the app is

LeafDoc is a **field scientific-imaging tool**: a pro manual camera (CameraX + Camera2) for capturing leaf/specimen images with structured metadata + GPS, optional panorama stitching, and **cloud AI disease diagnosis**. **MVVM + Jetpack Compose + Hilt + Room.** Target SDK 35, min SDK 26.

## Architecture & Key Flows

```
com.leafdoc.app/
├── camera/        # ProCameraController (CameraX+Camera2), HighResCaptureEngine (Camera2 max-res/RAW), FrameAnalyzer
├── stitching/     # SimpleStitcher (active), MidribAligner; ImageStitcher/OverlapGuide are unused
├── data/
│   ├── model/     # Room entities (LeafSession, LeafSegment) + enums (CameraSettings, CaptureFormat, FocusMode, ...)
│   ├── local/     # LeafDocDatabase (+ migrations), DAOs, Converters
│   ├── remote/ai/ # AiProvider interface + Gemini/Claude/ChatGpt providers, AiProviderFactory, prompts/
│   ├── repository/# LeafSessionRepository, ImageRepository, DiagnosisRepository
│   └── preferences/# UserPreferencesManager (DataStore)
├── di/            # Hilt modules (AppModule, AiModule, NetworkModule, DatabaseModule)
├── ui/            # camera/, gallery/, results/, dashboard/, settings/, components/, theme/
├── navigation/    # NavGraph (Compose Navigation)
└── util/          # TiffWriter, LocationManager
```

### Capture pipeline — two workflows, chosen in the start dialog (`SessionType`)

The pipeline is **frames-first**: stitching is optional, not the default.

- **SIMPLE mode** (fast field): an always-visible editable metadata bar (Farmer/Field/Treatment/Leaf). Capture **one** image → review (`SimpleReviewOverlay`) → Export saves a single-segment session **and** exports to the device gallery → returns to live camera. Driven by `CameraViewModel.startSimpleMode / onSimpleCaptured / exportSimpleCapture`.
- **DETAILED mode**: capture N frames (`onImageCaptured`) → `finishSessionFramesOnly()` saves them un-stitched (default). Stitching is an **opt-in** action from the Results screen ("Stitch Frames" card → `ResultsViewModel.prepareForAlignment → confirmAlignment`). `CameraViewModel.finishSession()`/`finishWithSingleSegment()` exist for a stitch-on-finish path but are not currently wired to a button.

A `LeafSession` with `stitchedImagePath == null` is a frames-only session; gallery/results fall back to the first segment (its displayable proxy) for the cover image.

### Capture: `ProCameraController` + `HighResCaptureEngine`

- **Standard capture** uses CameraX `ImageCapture` (JPEG from sensor) → decoded to a `Bitmap` → re-encoded to the chosen `CaptureFormat` by `ImageRepository`. The original bitmap is recycled after use (watch for leaks if you add capture paths).
- **Lens selection is zoom-ratio based**, not camera-ID based. Modern phones fuse ultra-wide/main/tele behind one logical camera; the UI exposes a zoom selector (`cameraControl.setZoomRatio`, 0.6×–N×). The old camera-ID `LensSelector` was removed.
- **Resolution**: per-lens sizes on `LensInfo`; capture uses `ResolutionSelector`. Selecting a `maxResSizes` entry routes capture to `HighResCaptureEngine`, which unbinds CameraX, opens the device with Camera2 `SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION`, writes the JPEG straight to a file (no giant bitmap), then re-binds CameraX. Gated on the **maximum-resolution stream map existing**, NOT the `ULTRA_HIGH_RESOLUTION_SENSOR` capability flag (Samsung leaves that unset). Many devices (incl. Samsung flagships) vendor-lock 50/200MP away from third-party apps — capture then falls back to the standard ~12MP.
- **Focus** (`FocusMode`): Continuous / Single(tap) / Macro / Infinity / Manual, plus tap-to-focus (with on-screen ring) and AF-lock (`setAfLocked` → `FocusMeteringAction.disableAutoCancel`).
- **Preview**: `PreviewView` is set to `ImplementationMode.COMPATIBLE` (TextureView). **Do not revert to SurfaceView** — it caused persistent black screens through Compose navigation transitions. `FIT_CENTER` scale gives a WYSIWYG (letterboxed) preview; `CameraViewModel.cropBitmap` maps crop-rect fractions assuming FIT_CENTER (changing scaleType breaks crop mapping).

### Capture formats (`CaptureFormat`)

| Format | In-app behavior |
| --- | --- |
| JPEG / PNG | Decodable by Android → render & AI-diagnose directly |
| **TIFF** | Lossless via hand-written `util/TiffWriter` (no dependency). Android **cannot decode TIFF**, so the app generates a ~1024px JPEG **proxy** for preview/AI; export **byte-copies** the TIFF losslessly. |
| RAW/DNG | Camera2 `RAW_SENSOR` + `DngCreator`; companion JPEG is the in-app segment, DNG saved to the shared gallery. |

When adding display/AI code, route TIFF/DNG through the JPEG proxy (`ResultsScreen.displayPath()` / `ResultsViewModel.decodablePath()`). Export uses `ImageRepository.exportRawBytes` and must route by directory: **MediaStore.Images allows only Pictures/DCIM; MediaStore.Files allows only Download/Documents** (don't put a Pictures path in the Files collection).

### Database (`LeafDocDatabase`)

- **Version 3.** Entities: `LeafSession` 1→N `LeafSegment` (CASCADE). Migrations in `ALL_MIGRATIONS`: v1→v2 added `frameLabel`; **v2→v3 added `treatment`**. Destructive migration is intentionally OFF — add a `Migration` for every schema change.
- Completing a session: `completeSession(id, stitchedPath)` (stitched) or `completeSessionWithoutStitch(id)` (frames-only).

### AI diagnosis

`AiProvider` strategy interface → `GeminiAiProvider` (gemini-2.5-flash, default), `ClaudeAiProvider` (claude-3-5-sonnet), `ChatGptAiProvider` (gpt-4o), selected via `AiProviderFactory`. `DiagnosisRepository.analyzeLeaf(imagePath, ...)` takes an arbitrary path (per-frame diagnosis works). `PromptLibrary` has 4 templates (quick_check / standard_analysis / detailed_diagnosis / research_mode) sharing a corn-disease DB and a common JSON output contract; all return a unified `DiagnosisDisplay`. Note: `data/remote/GeminiAiService.kt` and `DiagnosisApiService.kt` are legacy/dead — the live path is `data/remote/ai/`.

### Preferences (`UserPreferencesManager`, DataStore)

Persists `CameraSettings` (incl. `captureFormat`, `focusMode`), export settings, midrib/crop toggles, AI provider/prompt, and **user-managed pick lists** (`farmerIdOptions`, `fieldIdOptions`, `treatmentOptions`) surfaced as combo fields at capture and managed in Settings → "Saved Lists".

## UI conventions

- Leaf-green accent for active/selected controls is the local `LeafAccent = Color(0xFF60AD5E)` in `CameraScreen` (don't use cyan).
- Gallery/Results/Settings use a `SnackbarHost` for feedback; the camera screen (no Scaffold) shows transient messages/errors as auto-dismissing overlays.
- Camera top bar: Overview (dashboard) top-left, then the overlay-controls toggle (crop/midrib/align panel drops **below** the bar), zoom, resolution, RAW, focus, settings. Gallery button is bottom-left; the bottom-left slot becomes Cancel/Delete during a capture session.

## Configuration

API keys are read from `local.properties` into `BuildConfig`:
```properties
GEMINI_API_KEY=...
CLAUDE_API_KEY=...
CHATGPT_API_KEY=...
```
Keys: [Google AI Studio](https://aistudio.google.com/apikey), [Anthropic Console](https://console.anthropic.com/), [OpenAI Platform](https://platform.openai.com/).

## Logging

Timber. Full logging in debug; WARN/ERROR only in release. Initialized in `LeafDocApplication`.
