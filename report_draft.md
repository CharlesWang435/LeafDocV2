# LeafDoc Prototype Timeline & Testing Report (Draft)

## 1. Introduction

LeafDoc is a prototype system for corn leaf disease diagnosis using transmittance imaging. The system consists of two components:
- **Hardware:** A custom lightbox scanner for transmittance imaging
- **Software:** An Android application for image capture, stitching, and AI-powered diagnosis

This report documents the development timeline and testing of both components.

---

## 2. Software Development Timeline (LeafDoc App)

### 2.1 Overview

LeafDoc App is an Android application for corn leaf disease diagnosis using transmittance imaging. The app captures multiple leaf segments, stitches them into a panorama, and uses AI to analyze for diseases. The current version (V2) represents a complete architectural rebuild following lessons learned from an unsuccessful first iteration.

### 2.2 Version 1: Initial Attempt (October 2025)

**Approach:** Video-based capture with OpenCV frame extraction

| Date | Milestone | Description |
|------|-----------|-------------|
| Oct 21, 2025 | Initial Release | Video capture system with OpenCV 4.8.0 stitching |

**Technical Implementation:**
- Video recording with frame extraction at 100ms intervals
- OpenCV ORB feature matching for stitching with vertical stacking fallback
- Blur detection and duplicate frame removal
- Quality scoring system (30-95%)
- Traditional Android Views UI
- AI diagnosis planned but not implemented

**Outcome: Failure**

V1 was ultimately unsuccessful due to several critical issues:

1. **Stability:** The app crashed frequently during video processing and frame extraction
2. **OpenCV Integration:** Feature-based stitching with video frames proved difficult to implement reliably on Android
3. **Image Quality:** Resulting stitched images were extremely blurry and deformed, rendering them unusable for diagnosis
4. **Missing AI:** Disease diagnosis was only on the roadmap—never implemented
5. **UI/UX:** Generic interface with poor usability; gallery and settings were non-functional or unreliable

These failures informed the complete redesign approach for V2.

### 2.3 Version 2: Redesign (December 2025)

**Approach:** Discrete photo capture with custom stitching algorithm

| Phase | Date | Milestone | Key Changes |
|-------|------|-----------|-------------|
| 1 | Dec 10, 2025 | Foundation | New architecture (MVVM + Compose), CameraX with manual controls, Room database |
| 2 | Dec 10, 2025 | Stitching | Custom SimpleStitcher replacing OpenCV, MidribAligner for vertical correction |
| 3 | Dec 10, 2025 | UI Polish | Pro camera controls, lock overlays, refined button positioning |
| 4 | Dec 10, 2025 | AI & Viewing | Gemini 2.5 Flash integration, functional gallery, zoomable preview, results screen |

### 2.4 Key Architectural Decisions (V1 → V2)

| Problem in V1 | Solution in V2 |
|---------------|----------------|
| Video frame extraction caused crashes | Switched to discrete photo captures for stability |
| OpenCV stitching produced blurry/deformed images | Custom SimpleStitcher with gradient blending |
| No vertical alignment correction | MidribAligner using green channel dominance analysis |
| Generic, unreliable UI | Jetpack Compose with clean, functional gallery and settings |
| No AI diagnosis | Gemini 2.5 Flash fully integrated |
| Basic auto camera settings | Manual ISO, shutter speed, focus, white balance controls |

### 2.5 V2 Feature Details

**Camera System**
- CameraX with Camera2 interop for pro manual controls
- Crop rectangle overlay to match light board dimensions
- Midrib guide for consistent segment alignment

**Image Stitching**
- SimpleStitcher: Horizontal concatenation with configurable overlap (default 10%)
- Linear gradient blending for seamless transitions
- MidribAligner: Detects leaf midrib via green channel dominance ratio, calculates vertical offsets to correct hand movement drift

**AI Diagnosis**
- Google Gemini 2.5 Flash integration
- Returns: health score (0-100), disease identification, confidence level, treatment suggestions
- Supports 11+ corn diseases including Northern Corn Leaf Blight, Gray Leaf Spot, Common Rust, Anthracnose

**User Interface**
- Clean, functional gallery with grid view and pinch-to-zoom preview
- Settings that persist correctly via DataStore
- Expandable pro controls panel minimizing screen obstruction
- Lock controls for overlay positions

### 2.6 Tech Stack Comparison

| Component | V1 | V2 |
|-----------|----|----|
| Language | Kotlin + Java | Kotlin |
| UI | Android Views | Jetpack Compose |
| Architecture | Activity-based | MVVM + Hilt DI |
| Camera | CameraX 1.3.1 | CameraX 1.4.0 + Camera2 interop |
| Image Processing | OpenCV 4.8.0 | Custom algorithms |
| Database | JSON files | Room 2.6.1 |
| AI | None (planned GPT-4) | Gemini 2.5 Flash |
| Min SDK | 24 | 26 |
| Target SDK | 34 | 35 |

---

## 3. Hardware Development Timeline

*(To be added - SolidWorks materials pending)*

---

## 4. Testing & Validation

### 4.1 Software Testing

*(To be added)*

### 4.2 Hardware Testing

*(To be added)*

### 4.3 Integration Testing

*(To be added)*

---

## 5. Results & Discussion

*(To be added)*

---

## 6. Conclusion

*(To be added)*

---

## Appendices

### A. V1 Repository
- GitHub: https://github.com/CharlesWang435/LeafDoc

### B. V2 Repository
- Local development: LeafDocV2

### C. Hardware CAD Drawings
*(To be added)*
