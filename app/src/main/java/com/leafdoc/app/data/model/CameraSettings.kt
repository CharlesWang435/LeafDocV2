package com.leafdoc.app.data.model

/**
 * Camera settings for pro manual control mode.
 */
data class CameraSettings(
    val iso: Int = ISO_AUTO,
    val shutterSpeed: Long = SHUTTER_AUTO,  // in nanoseconds
    val focusDistance: Float = FOCUS_AUTO,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val exposureCompensation: Float = 0f,
    val resolution: ResolutionMode = ResolutionMode.FULL,
    val flashMode: FlashMode = FlashMode.OFF,
    val gridOverlay: GridOverlayType = GridOverlayType.THIRDS,
    val showHistogram: Boolean = true,
    val showFocusPeaking: Boolean = false,
    val showZebras: Boolean = false,
    val zebraThreshold: Int = 95  // percentage
) {
    companion object {
        const val ISO_AUTO = -1
        const val SHUTTER_AUTO = -1L
        const val FOCUS_AUTO = -1f

        val ISO_VALUES = listOf(ISO_AUTO, 50, 100, 200, 400, 800, 1600, 3200, 6400)

        // Shutter speeds in nanoseconds (1/x seconds)
        val SHUTTER_SPEEDS = listOf(
            SHUTTER_AUTO,
            1_000_000_000L / 8000,   // 1/8000
            1_000_000_000L / 4000,   // 1/4000
            1_000_000_000L / 2000,   // 1/2000
            1_000_000_000L / 1000,   // 1/1000
            1_000_000_000L / 500,    // 1/500
            1_000_000_000L / 250,    // 1/250
            1_000_000_000L / 125,    // 1/125
            1_000_000_000L / 60,     // 1/60
            1_000_000_000L / 30,     // 1/30
            1_000_000_000L / 15,     // 1/15
            1_000_000_000L / 8,      // 1/8
            1_000_000_000L / 4,      // 1/4
            1_000_000_000L / 2,      // 1/2
            1_000_000_000L,          // 1s
            2_000_000_000L,          // 2s
            4_000_000_000L           // 4s
        )

        fun shutterSpeedToString(ns: Long): String {
            return when {
                ns == SHUTTER_AUTO -> "Auto"
                ns >= 1_000_000_000L -> "${ns / 1_000_000_000}s"
                else -> "1/${1_000_000_000L / ns}"
            }
        }

        fun isoToString(iso: Int): String {
            return if (iso == ISO_AUTO) "Auto" else iso.toString()
        }
    }
}

enum class WhiteBalanceMode(val displayName: String, val temperature: Int?) {
    AUTO("Auto", null),
    DAYLIGHT("Daylight", 5500),
    CLOUDY("Cloudy", 6500),
    TUNGSTEN("Tungsten", 2850),
    FLUORESCENT("Fluorescent", 4000),
    FLASH("Flash", 5500),
    SHADE("Shade", 7500),
    CUSTOM("Custom", null)
}

enum class ResolutionMode(val displayName: String, val megapixels: Float?) {
    FULL("Full Resolution", null),
    HIGH("High (12MP)", 12f),
    MEDIUM("Medium (8MP)", 8f),
    LOW("Low (4MP)", 4f)
}

enum class FlashMode(val displayName: String) {
    OFF("Off"),
    ON("On"),
    AUTO("Auto"),
    TORCH("Torch")
}

enum class GridOverlayType(val displayName: String) {
    NONE("None"),
    THIRDS("Rule of Thirds"),
    GOLDEN("Golden Ratio"),
    CENTER("Center Cross"),
    GRID_4X4("4x4 Grid")
}
