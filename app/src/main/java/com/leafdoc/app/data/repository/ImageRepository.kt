package com.leafdoc.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.leafdoc.app.data.model.CaptureFormat
import com.leafdoc.app.data.model.ExportSettings
import com.leafdoc.app.data.model.ImageFormat
import com.leafdoc.app.data.model.ExportLocation
import com.leafdoc.app.data.model.LeafSession
import com.leafdoc.app.data.model.LeafSegment
import com.leafdoc.app.util.TiffWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageRepository @Inject constructor(
    private val context: Context
) {
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    companion object {
        // 95% quality is visually identical to 100% but ~40% smaller file size
        private const val DEFAULT_JPEG_QUALITY = 95
        private const val THUMBNAIL_QUALITY = 80
    }

    private val segmentsDir: File by lazy {
        File(context.filesDir, "segments").apply { mkdirs() }
    }

    private val stitchedDir: File by lazy {
        File(context.filesDir, "stitched").apply { mkdirs() }
    }

    private val thumbnailDir: File by lazy {
        File(context.cacheDir, "thumbnails").apply { mkdirs() }
    }

    suspend fun saveSegmentImage(
        bitmap: Bitmap,
        sessionId: String,
        segmentIndex: Int,
        format: CaptureFormat = CaptureFormat.JPEG,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): String = withContext(Dispatchers.IO) {
        val sessionDir = File(segmentsDir, sessionId).apply { mkdirs() }
        val fileName = "segment_${segmentIndex}_${dateFormat.format(Date())}.${captureExtension(format)}"
        val file = File(sessionDir, fileName)

        FileOutputStream(file).use { out ->
            writeBitmap(bitmap, out, format, quality)
        }

        file.absolutePath
    }

    suspend fun saveStitchedImage(
        bitmap: Bitmap,
        sessionId: String,
        format: CaptureFormat = CaptureFormat.JPEG,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): String = withContext(Dispatchers.IO) {
        val fileName = "stitched_${sessionId}_${dateFormat.format(Date())}.${captureExtension(format)}"
        val file = File(stitchedDir, fileName)

        FileOutputStream(file).use { out ->
            writeBitmap(bitmap, out, format, quality)
        }

        file.absolutePath
    }

    /** File extension for a captured segment/stitched image in the given format. */
    private fun captureExtension(format: CaptureFormat): String = when (format) {
        CaptureFormat.JPEG -> "jpg"
        CaptureFormat.PNG -> "png"
        CaptureFormat.TIFF -> "tiff"
        CaptureFormat.RAW_DNG -> "jpg" // RAW path handled separately; defensive fallback
    }

    /** Encodes [bitmap] to [out] in the given capture format (lossless for PNG/TIFF). */
    private fun writeBitmap(bitmap: Bitmap, out: OutputStream, format: CaptureFormat, quality: Int) {
        when (format) {
            CaptureFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            CaptureFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            CaptureFormat.TIFF -> TiffWriter.write(bitmap, out)
            CaptureFormat.RAW_DNG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    }

    /**
     * Creates a displayable JPEG thumbnail directly from an in-memory bitmap.
     * Used at capture time so PNG/TIFF segments (which Coil/BitmapFactory can't render)
     * still have a viewable thumbnail in the gallery and results screens.
     */
    suspend fun createThumbnailFromBitmap(
        bitmap: Bitmap,
        sessionId: String,
        segmentIndex: Int,
        maxSize: Int = 256
    ): String = withContext(Dispatchers.IO) {
        val thumbnailFile = File(thumbnailDir, "thumb_${sessionId}_${segmentIndex}_${dateFormat.format(Date())}.jpg")

        val scale = (maxOf(bitmap.width, bitmap.height) / maxSize).coerceAtLeast(1)
        val thumb = if (scale > 1) {
            Bitmap.createScaledBitmap(bitmap, bitmap.width / scale, bitmap.height / scale, true)
        } else {
            bitmap
        }

        FileOutputStream(thumbnailFile).use { out ->
            thumb.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
        }
        if (thumb != bitmap) thumb.recycle()

        thumbnailFile.absolutePath
    }

    suspend fun createThumbnail(
        imagePath: String,
        maxSize: Int = 256
    ): String = withContext(Dispatchers.IO) {
        val originalFile = File(imagePath)
        val thumbnailFile = File(thumbnailDir, "thumb_${originalFile.name}")

        if (thumbnailFile.exists()) {
            return@withContext thumbnailFile.absolutePath
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imagePath, options)

        val scale = maxOf(options.outWidth, options.outHeight) / maxSize
        options.inSampleSize = scale.coerceAtLeast(1)
        options.inJustDecodeBounds = false

        val bitmap = BitmapFactory.decodeFile(imagePath, options)
            ?: return@withContext imagePath

        FileOutputStream(thumbnailFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        bitmap.recycle()

        thumbnailFile.absolutePath
    }

    suspend fun loadBitmap(imagePath: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            BitmapFactory.decodeFile(imagePath)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loadBitmaps(imagePaths: List<String>): List<Bitmap> = withContext(Dispatchers.IO) {
        imagePaths.mapNotNull { path ->
            try {
                BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun exportImage(
        imagePath: String,
        settings: ExportSettings,
        customFileName: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        val sourceFile = File(imagePath)
        if (!sourceFile.exists()) return@withContext null

        val bitmap = BitmapFactory.decodeFile(imagePath) ?: return@withContext null

        val fileName = customFileName
            ?: "LeafDoc_${dateFormat.format(Date())}.${settings.format.extension}"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportToMediaStore(bitmap, fileName, settings)
            } else {
                exportToExternalStorage(bitmap, fileName, settings)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun exportToMediaStore(
        bitmap: Bitmap,
        fileName: String,
        settings: ExportSettings
    ): Uri? {
        val relativePath = when (settings.exportLocation) {
            ExportLocation.PICTURES_FOLDER -> "${Environment.DIRECTORY_PICTURES}/LeafDoc"
            ExportLocation.DOCUMENTS_FOLDER -> "${Environment.DIRECTORY_DOCUMENTS}/LeafDoc"
            ExportLocation.DOWNLOADS_FOLDER -> "${Environment.DIRECTORY_DOWNLOADS}/LeafDoc"
            ExportLocation.CUSTOM -> "${Environment.DIRECTORY_PICTURES}/LeafDoc"
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, settings.format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return null

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            writeBitmapToStream(bitmap, outputStream, settings)
        }

        return uri
    }

    private fun exportToExternalStorage(
        bitmap: Bitmap,
        fileName: String,
        settings: ExportSettings
    ): Uri? {
        val baseDir = when (settings.exportLocation) {
            ExportLocation.PICTURES_FOLDER ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            ExportLocation.DOCUMENTS_FOLDER ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            ExportLocation.DOWNLOADS_FOLDER ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ExportLocation.CUSTOM ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        }

        val leafDocDir = File(baseDir, "LeafDoc").apply { mkdirs() }
        val file = File(leafDocDir, fileName)

        FileOutputStream(file).use { outputStream ->
            writeBitmapToStream(bitmap, outputStream, settings)
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Exports all segments from a session as individual image files.
     * Returns list of exported URIs.
     */
    suspend fun exportAllSegments(
        session: LeafSession,
        segments: List<LeafSegment>,
        settings: ExportSettings,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<Uri> = withContext(Dispatchers.IO) {
        val exportedUris = mutableListOf<Uri>()

        segments.forEachIndexed { index, segment ->
            onProgress?.invoke(index + 1, segments.size)

            val fileName = buildSegmentFileName(session, segment, settings.format)
            val uri = exportImage(segment.imagePath, settings, fileName)

            if (uri != null) {
                exportedUris.add(uri)
            }
        }

        exportedUris
    }

    private fun buildSegmentFileName(
        session: LeafSession,
        segment: LeafSegment,
        format: ImageFormat
    ): String {
        val parts = mutableListOf<String>()
        parts.add("LeafDoc")

        if (session.farmerId.isNotEmpty()) {
            parts.add(session.farmerId.take(20))
        }
        if (session.fieldId.isNotEmpty()) {
            parts.add(session.fieldId.take(20))
        }
        parts.add("Leaf${session.leafNumber}")

        // Add frame label if present
        if (segment.frameLabel != null) {
            parts.add(segment.frameLabel.replace(" ", "_"))  // "Frame_3"
        } else {
            parts.add("Segment${segment.segmentIndex + 1}")
        }

        val timestamp = dateFormat.format(Date(segment.capturedAt))
        parts.add(timestamp)

        return "${parts.joinToString("_")}.${format.extension}"
    }

    private fun writeBitmapToStream(
        bitmap: Bitmap,
        outputStream: OutputStream,
        settings: ExportSettings
    ) {
        when (settings.format) {
            ImageFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, settings.quality, outputStream)
            ImageFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            ImageFormat.TIFF -> TiffWriter.write(bitmap, outputStream)
        }
    }

    suspend fun deleteSessionImages(sessionId: String) = withContext(Dispatchers.IO) {
        // Delete segments
        val sessionSegmentsDir = File(segmentsDir, sessionId)
        sessionSegmentsDir.deleteRecursively()

        // Delete stitched images for this session
        stitchedDir.listFiles()?.filter { it.name.contains(sessionId) }?.forEach {
            it.delete()
        }
    }

    suspend fun deleteImage(imagePath: String) = withContext(Dispatchers.IO) {
        File(imagePath).delete()
    }

    suspend fun getStorageUsage(): StorageInfo = withContext(Dispatchers.IO) {
        val segmentsSize = segmentsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val stitchedSize = stitchedDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val thumbnailSize = thumbnailDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        StorageInfo(
            segmentsBytes = segmentsSize,
            stitchedBytes = stitchedSize,
            thumbnailBytes = thumbnailSize,
            totalBytes = segmentsSize + stitchedSize + thumbnailSize
        )
    }

    suspend fun clearThumbnailCache() = withContext(Dispatchers.IO) {
        thumbnailDir.deleteRecursively()
        thumbnailDir.mkdirs()
    }

    fun getImageUri(imagePath: String): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(imagePath)
        )
    }
}

data class StorageInfo(
    val segmentsBytes: Long,
    val stitchedBytes: Long,
    val thumbnailBytes: Long,
    val totalBytes: Long
) {
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }
}
