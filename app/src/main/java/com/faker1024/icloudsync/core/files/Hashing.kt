package com.faker1024.icloudsync.core.files

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class DigestResult(
    val sha256: String,
    val byteCount: Long,
)

object Hashing {
    private const val BUFFER_SIZE = 128 * 1024

    fun copyAndDigest(
        input: InputStream,
        output: OutputStream,
        onBytesCopied: (Long) -> Unit = {},
    ): DigestResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            total += count
            onBytesCopied(total)
        }
        output.flush()
        return DigestResult(
            sha256 = digest.digest().joinToString(separator = "") { "%02x".format(it) },
            byteCount = total,
        )
    }

    fun sha256(
        input: InputStream,
        onBytesRead: (Long) -> Unit = {},
    ): DigestResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            total += count
            onBytesRead(total)
        }
        return DigestResult(
            sha256 = digest.digest().joinToString(separator = "") { "%02x".format(it) },
            byteCount = total,
        )
    }
}
