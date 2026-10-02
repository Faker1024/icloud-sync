package com.faker1024.icloudsync.core.icloud

import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Strict guards for the private Drive trash API. Never identifies a remote file by its name. */
internal object ICloudTrashProtocol {
    data class VerifiedFile(val remoteId: String, val etag: String?, val alreadyTrashed: Boolean)

    fun accountKey(appleId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(appleId.trim().lowercase(Locale.US).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun detailsRequest(remoteId: String): JSONArray {
        requireFileId(remoteId)
        return JSONArray().put(
            JSONObject().put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("drivewsid", remoteId)
                        .put("partialData", false)
                        .put("includeHierarchy", false),
                ),
            ),
        )
    }

    fun verifyDetails(
        body: String,
        remoteId: String,
        expectedSize: Long,
        expectedModifiedAtMillis: Long,
    ): VerifiedFile {
        requireFileId(remoteId)
        if (expectedSize < 0L || expectedModifiedAtMillis <= 0L) {
            fail("缺少原始同步记录，无法安全核对云端文件，请重新同步后再删除")
        }
        val items = try {
            JSONArray(body)
        } catch (_: Exception) {
            fail("无法解析云端文件核对结果，本地文件已保留")
        }
        val item = exactlyOneItem(items)
        if (item.strictString("drivewsid") != remoteId) {
            fail("云端返回的文件标识不匹配，本地文件已保留")
        }
        val status = item.strictString("status")
        if (item.has("status") && status != "OK" && status != "ID_TRASHED") {
            fail(statusMessage(status))
        }
        if (item.strictString("type") != "FILE") {
            fail("云端目标不是普通文件，已停止删除")
        }
        val size = item.opt("size")?.toString()?.toLongOrNull()
        val modifiedSeconds = modifiedSeconds(item.opt("dateModified"))
        // SyncedFileMetadata stores the remote timestamp with whole-second precision.
        if (size != expectedSize || modifiedSeconds != expectedModifiedAtMillis / 1_000L) {
            fail("云端文件在同步后已更改，已保留本地文件；请重新同步并扫描")
        }
        val alreadyTrashed = status == "ID_TRASHED"
        val etag = item.strictString("etag")?.takeIf(String::isNotBlank)
        if (!alreadyTrashed && etag == null) {
            fail("云端未返回文件版本，已停止删除")
        }
        return VerifiedFile(remoteId, etag, alreadyTrashed)
    }

    fun trashRequest(file: VerifiedFile): JSONObject {
        requireFileId(file.remoteId)
        if (file.alreadyTrashed || file.etag.isNullOrBlank()) fail("文件删除前核对信息无效")
        return JSONObject().put(
            "items",
            JSONArray().put(
                JSONObject()
                    .put("drivewsid", file.remoteId)
                    .put("etag", file.etag)
                    .put("clientId", file.remoteId),
            ),
        )
    }

    fun verifyTrashResult(body: String, remoteId: String) {
        requireFileId(remoteId)
        val response = try {
            JSONObject(body)
        } catch (_: Exception) {
            fail("无法确认云端删除结果，本地文件已保留")
        }
        val item = exactlyOneItem(response.optJSONArray("items"))
        if (item.strictString("drivewsid") != remoteId) {
            fail("云端删除回执的文件标识不匹配，本地文件已保留")
        }
        if (item.strictString("status") != "OK") fail(statusMessage(item.strictString("status")))
    }

    private fun requireFileId(remoteId: String) {
        val parts = remoteId.split("::")
        if (parts.size != 3 || parts[0] != "FILE" || parts.any(String::isBlank)) {
            fail("缺少可靠的云端文件标识，已停止删除")
        }
    }

    private fun exactlyOneItem(items: JSONArray?): JSONObject {
        if (items?.length() != 1) fail("云端未返回唯一文件结果，本地文件已保留")
        return items.optJSONObject(0) ?: fail("云端文件结果无效，本地文件已保留")
    }

    private fun JSONObject.strictString(key: String): String? = opt(key) as? String

    private fun modifiedSeconds(raw: Any?): Long? {
        val value = when (raw) {
            is String -> raw
            is Number -> raw.toString()
            else -> return null
        }
        val numeric = value.toLongOrNull()
        if (numeric != null) {
            return (if (numeric >= 1_000_000_000_000L) numeric / 1_000L else numeric)
                .takeIf { it > 0L }
        }
        return runCatching { Instant.parse(value).epochSecond }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().epochSecond }
            .getOrNull()?.takeIf { it > 0L }
    }

    private fun statusMessage(status: String?): String = when (status) {
        "ETAG_CONFLICT" -> "云端文件版本已变化，已停止删除；请重新同步并扫描"
        "ID_INVALID", "NOT_FOUND", "ID_NOT_FOUND" -> "无法核对原云端文件，本地文件已保留"
        "ID_TRASHED" -> "云端文件已在最近删除中，但回执不足以核对，本地文件已保留"
        else -> "iCloud 未确认文件删除，本地文件已保留"
    }

    private fun fail(message: String): Nothing = throw ICloudApiException(ICloudError.INVALID_RESPONSE, message)
}
