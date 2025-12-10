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
import com.leafdoc.app.data.model.ExportSettings
import com.leafdoc.app.data.model.ImageFormat
import com.leafdoc.app.data.model.ExportLocation
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
        quality: Int = DEFAULT_JPEG_QUALITY
    ): String = withContext(Dispatchers.IO) {
        val sessionDir = File(segmentsDir, sessionId).apply { mkdirs() }
        val fileName = "segment_${segmentIndex}_${dateFormat.format(Date())}.jpg"
        val file = File(sessionDir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }

        file.absolutePath
    }

    suspend fun saveStitchedImage(
        bitmap: Bitmap,
        sessionId: String,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): String = withContext(Dispatchers.IO) {
        val fileName = "stitched_${sessionId}_${dateFormat.format(Date())}.jpg"
        val file = File(stitchedDir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }

        file.absolutePath
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

    private fun writeBitmapToStream(
        bitmap: Bitmap,
        outputStream: OutputStream,
        settings: ExportSettings
    ) {
        val format = when (settings.format) {
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            ImageFormat.TIFF -> Bitmap.CompressFormat.PNG // Android doesn't support TIFF natively
        }

        val quality = if (settings.format.supportsQuality) settings.quality else 100
        bitmap.compress(format, quality, outputStream)
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
