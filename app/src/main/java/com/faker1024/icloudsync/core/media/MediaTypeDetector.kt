package com.faker1024.icloudsync.core.media

import com.faker1024.icloudsync.domain.model.MediaKind
import java.io.File
import java.io.FileInputStream
import java.util.Locale

object MediaTypeDetector {
    private val rawExtensions = setOf("dng", "arw", "cr2", "cr3", "nef", "orf", "raf", "rw2")

    fun detect(file: File, originalName: String): DetectedMedia? {
        val header = ByteArray(32)
        val count = FileInputStream(file).use { it.read(header) }.coerceAtLeast(0)
        val extension = originalName.substringAfterLast('.', "").lowercase(Locale.US)

        return when {
            count >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
                header[2] == 0xFF.toByte() -> detected(MediaKind.IMAGE, "image/jpeg", originalName)

            count >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) ->
                detected(MediaKind.IMAGE, "image/png", originalName)

            count >= 6 && String(header, 0, 6, Charsets.US_ASCII).startsWith("GIF8") ->
                detected(MediaKind.IMAGE, "image/gif", originalName)

            count >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP" ->
                detected(MediaKind.IMAGE, "image/webp", originalName)

            count >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" ->
                detectIsoMedia(String(header, 8, 4, Charsets.US_ASCII), originalName)

            extension in rawExtensions && looksLikeRaw(header, count) ->
                detected(MediaKind.RAW, rawMimeType(extension), originalName)

            else -> null
        }
    }

    fun looksLikeZip(file: File, displayName: String?, mimeType: String?): Boolean {
        if (mimeType?.lowercase(Locale.US) in ZIP_MIME_TYPES) return true
        if (displayName?.lowercase(Locale.US)?.endsWith(".zip") == true) return true
        val signature = ByteArray(4)
        val read = runCatching { FileInputStream(file).use { it.read(signature) } }.getOrDefault(-1)
        return read == 4 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte() &&
            signature[2] in setOf(3, 5, 7).map(Int::toByte) && signature[3] in setOf(4, 6, 8).map(Int::toByte)
    }

    private fun detectIsoMedia(brand: String, originalName: String): DetectedMedia? = when (brand) {
        "heic", "heix", "hevc", "hevx", "mif1", "msf1" ->
            detected(MediaKind.IMAGE, "image/heic", originalName)

        "crx " -> detected(MediaKind.RAW, "image/x-canon-cr3", originalName)
        "qt  " -> detected(MediaKind.VIDEO, "video/quicktime", originalName)
        "isom", "iso2", "mp41", "mp42", "avc1", "M4V " ->
            detected(MediaKind.VIDEO, "video/mp4", originalName)

        else -> when (originalName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "heic", "heif" -> detected(MediaKind.IMAGE, "image/heic", originalName)
            "mov" -> detected(MediaKind.VIDEO, "video/quicktime", originalName)
            "mp4", "m4v" -> detected(MediaKind.VIDEO, "video/mp4", originalName)
            else -> null
        }
    }

    private fun rawMimeType(extension: String): String = when (extension) {
        "dng" -> "image/x-adobe-dng"
        else -> "image/x-$extension"
    }

    private fun detected(kind: MediaKind, mimeType: String, originalName: String) =
        DetectedMedia(kind, mimeType, originalName)

    private fun looksLikeRaw(header: ByteArray, count: Int): Boolean {
        if (count >= 4) {
            val littleEndianTiff = header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 0x2A.toByte() && header[3] == 0x00.toByte()
            val bigEndianTiff = header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() &&
                header[2] == 0x00.toByte() && header[3] == 0x2A.toByte()
            if (littleEndianTiff || bigEndianTiff) return true
        }
        return count >= 16 && String(header, 0, 16, Charsets.US_ASCII) == "FUJIFILMCCD-RAW "
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val ZIP_MIME_TYPES = setOf("application/zip", "application/x-zip-compressed")
}
