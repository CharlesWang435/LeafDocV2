package com.leafdoc.app.data.model

/**
 * Settings for exporting stitched leaf images.
 */
data class ExportSettings(
    val format: ImageFormat = ImageFormat.JPEG,
    val quality: Int = 95,  // 0-100 for JPEG
    val includeMetadata: Boolean = true,
    val exportLocation: ExportLocation = ExportLocation.PICTURES_FOLDER
)

enum class ImageFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val supportsQuality: Boolean
) {
    JPEG("JPEG", "jpg", "image/jpeg", true),
    PNG("PNG", "png", "image/png", false),
    TIFF("TIFF", "tiff", "image/tiff", false)
}

enum class ExportLocation(val displayName: String) {
    PICTURES_FOLDER("Pictures/LeafDoc"),
    DOCUMENTS_FOLDER("Documents/LeafDoc"),
    DOWNLOADS_FOLDER("Downloads/LeafDoc"),
    CUSTOM("Custom Location")
}
