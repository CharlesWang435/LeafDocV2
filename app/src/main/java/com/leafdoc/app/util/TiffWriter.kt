package com.leafdoc.app.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Minimal baseline TIFF encoder: uncompressed, 8-bit RGB, little-endian, single strip.
 *
 * Android has no built-in TIFF support (Bitmap.compress only does JPEG/PNG/WEBP), so we
 * write the TIFF structure by hand. Output is fully lossless and readable by standard
 * tools (Lightroom, ImageJ, Photoshop, RawTherapee, etc.).
 */
object TiffWriter {

    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5

    fun write(bitmap: Bitmap, out: OutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val samplesPerPixel = 3
        val stripByteCount = width.toLong() * height.toLong() * samplesPerPixel

        // Fixed 12-entry IFD; compute the offsets of the out-of-line tag data and pixels.
        val entryCount = 12
        val ifdOffset = 8
        val ifdSize = 2 + entryCount * 12 + 4
        val bitsPerSampleOffset = ifdOffset + ifdSize   // 3 SHORTs = 6 bytes
        val xResOffset = bitsPerSampleOffset + 6         // RATIONAL = 8 bytes
        val yResOffset = xResOffset + 8                  // RATIONAL = 8 bytes
        val pixelDataOffset = yResOffset + 8

        val header = LeBuffer()
        // --- TIFF header ---
        header.writeByte(0x49); header.writeByte(0x49)   // "II" => little-endian
        header.writeShort(42)                            // magic
        header.writeInt(ifdOffset)

        // --- Image File Directory ---
        header.writeShort(entryCount)
        header.writeEntry(256, TYPE_LONG, 1, width)                      // ImageWidth
        header.writeEntry(257, TYPE_LONG, 1, height)                     // ImageLength
        header.writeEntry(258, TYPE_SHORT, 3, bitsPerSampleOffset)       // BitsPerSample -> 8,8,8
        header.writeEntry(259, TYPE_SHORT, 1, 1)                         // Compression = none
        header.writeEntry(262, TYPE_SHORT, 1, 2)                         // PhotometricInterpretation = RGB
        header.writeEntry(273, TYPE_LONG, 1, pixelDataOffset)            // StripOffsets
        header.writeEntry(277, TYPE_SHORT, 1, samplesPerPixel)           // SamplesPerPixel
        header.writeEntry(278, TYPE_LONG, 1, height)                     // RowsPerStrip (single strip)
        header.writeEntry(279, TYPE_LONG, 1, stripByteCount.toInt())     // StripByteCounts
        header.writeEntry(282, TYPE_RATIONAL, 1, xResOffset)             // XResolution
        header.writeEntry(283, TYPE_RATIONAL, 1, yResOffset)             // YResolution
        header.writeEntry(296, TYPE_SHORT, 1, 2)                         // ResolutionUnit = inch
        header.writeInt(0)                                               // next IFD = none

        // --- Out-of-line tag data ---
        header.writeShort(8); header.writeShort(8); header.writeShort(8) // BitsPerSample
        header.writeInt(72); header.writeInt(1)                          // XResolution 72/1
        header.writeInt(72); header.writeInt(1)                          // YResolution 72/1

        out.write(header.toByteArray())

        // --- Pixel data, one row at a time to keep memory bounded for large images ---
        val rowPixels = IntArray(width)
        val rowBytes = ByteArray(width * samplesPerPixel)
        for (y in 0 until height) {
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
            var bi = 0
            for (x in 0 until width) {
                val p = rowPixels[x]
                rowBytes[bi++] = ((p shr 16) and 0xFF).toByte() // R
                rowBytes[bi++] = ((p shr 8) and 0xFF).toByte()  // G
                rowBytes[bi++] = (p and 0xFF).toByte()          // B
            }
            out.write(rowBytes)
        }
        out.flush()
    }

    /** Little-endian byte buffer with TIFF IFD-entry helper. */
    private class LeBuffer {
        private val buf = ByteArrayOutputStream()
        fun writeByte(v: Int) = buf.write(v and 0xFF)
        fun writeShort(v: Int) {
            buf.write(v and 0xFF)
            buf.write((v shr 8) and 0xFF)
        }
        fun writeInt(v: Int) {
            buf.write(v and 0xFF)
            buf.write((v shr 8) and 0xFF)
            buf.write((v shr 16) and 0xFF)
            buf.write((v shr 24) and 0xFF)
        }
        // For SHORT-typed single values the value sits in the low 2 bytes; writeInt zero-pads
        // the upper 2 bytes, which is exactly what the TIFF spec requires.
        fun writeEntry(tag: Int, type: Int, count: Int, value: Int) {
            writeShort(tag); writeShort(type); writeInt(count); writeInt(value)
        }
        fun toByteArray(): ByteArray = buf.toByteArray()
    }
}
