package com.faker1024.icloudsync.core.icloud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ICloudTrashProtocolTest {
    private val remoteId = "FILE::com.apple.CloudDocs::photo-1"
    private val modifiedAtMillis = 1_787_574_600_000L // 2026-08-24T12:30:00Z

    @Test
    fun `account keys normalize without exposing the account`() {
        val key = ICloudTrashProtocol.accountKey(" User@Example.com ")
        assertEquals(64, key.length)
        assertEquals(key, ICloudTrashProtocol.accountKey("user@example.com"))
        assertNotEquals(key, ICloudTrashProtocol.accountKey("other@example.com"))
        assertFalse(key.contains("example"))
    }

    @Test
    fun `preflight request targets only exact file without children`() {
        val request = ICloudTrashProtocol.detailsRequest(remoteId)
        assertEquals(1, request.length())
        val items = request.getJSONObject(0).getJSONArray("items")
        assertEquals(1, items.length())
        assertEquals(remoteId, items.getJSONObject(0).getString("drivewsid"))
        assertFalse(items.getJSONObject(0).getBoolean("partialData"))
        assertFalse(items.getJSONObject(0).getBoolean("includeHierarchy"))
    }

    @Test
    fun `matching fingerprint produces current etag trash request`() {
        val file = verify(details())
        assertEquals("current-version", file.etag)
        assertFalse(file.alreadyTrashed)
        val request = ICloudTrashProtocol.trashRequest(file)
        val item = request.getJSONArray("items").getJSONObject(0)
        assertEquals(remoteId, item.getString("drivewsid"))
        assertEquals(remoteId, item.getString("clientId"))
        assertEquals("current-version", item.getString("etag"))
        assertFalse(request.has("force"))
        assertFalse(item.has("force"))
    }

    @Test
    fun `remote fractional time matches stored second precision`() {
        val item = details().put("dateModified", "2026-08-24T12:30:00.789Z")
        assertEquals("current-version", verify(item).etag)
        assertEquals("current-version", verify(details().put("dateModified", modifiedAtMillis)).etag)
        assertEquals("current-version", verify(details().put("dateModified", modifiedAtMillis / 1_000L)).etag)
    }

    @Test
    fun `preflight rejects missing malformed and multiple records`() {
        listOf("", "not json", "{}", "[]", "[null]", "[true]", "[{},{}]").forEach { body ->
            assertThrows(body, ICloudApiException::class.java) {
                ICloudTrashProtocol.verifyDetails(body, remoteId, 123L, modifiedAtMillis)
            }
        }
    }

    @Test
    fun `preflight refuses mismatched identity or unknown file identifiers`() {
        rejects(details().put("drivewsid", "FILE::com.apple.CloudDocs::different"))
        rejects(details().apply { remove("drivewsid") })
        listOf("", "legacy:path", "FOLDER::com.apple.CloudDocs::root", "FILE::::id", "FILE::zone::id::extra").forEach {
            assertThrows(ICloudApiException::class.java) { ICloudTrashProtocol.detailsRequest(it) }
        }
    }

    @Test
    fun `preflight refuses folders and missing type`() {
        listOf("FOLDER", "APP_CONTAINER", "APP_LIBRARY", "UNKNOWN").forEach { type ->
            rejects(details().put("type", type))
        }
        rejects(details().apply { remove("type") })
    }

    @Test
    fun `preflight refuses changed or absent size`() {
        rejects(details().put("size", 124L))
        rejects(details().put("size", 122L))
        rejects(details().put("size", -1L))
        rejects(details().put("size", JSONObject.NULL))
        rejects(details().put("size", "123.5"))
        rejects(details().apply { remove("size") })
    }

    @Test
    fun `preflight refuses changed or unavailable modification time`() {
        rejects(details().put("dateModified", "2026-08-24T12:30:01Z"))
        rejects(details().put("dateModified", "invalid"))
        rejects(details().apply { remove("dateModified") })
        assertThrows(ICloudApiException::class.java) {
            ICloudTrashProtocol.verifyDetails(JSONArray().put(details()).toString(), remoteId, 123L, 0L)
        }
        assertThrows(ICloudApiException::class.java) {
            ICloudTrashProtocol.verifyDetails(JSONArray().put(details()).toString(), remoteId, -1L, modifiedAtMillis)
        }
    }

    @Test
    fun `preflight refuses absent empty or malformed etag`() {
        rejects(details().apply { remove("etag") })
        rejects(details().put("etag", ""))
        rejects(details().put("etag", " "))
        rejects(details().put("etag", 17))
    }

    @Test
    fun `preflight never treats not found or etag conflict as deleted`() {
        listOf("ID_INVALID", "NOT_FOUND", "ETAG_CONFLICT", "ERROR").forEach { status ->
            rejects(details().put("status", status))
        }
    }

    @Test
    fun `already trashed requires complete exact matching fingerprint`() {
        val file = verify(details().put("status", "ID_TRASHED").apply { remove("etag") })
        assertTrue(file.alreadyTrashed)
        assertThrows(ICloudApiException::class.java) { ICloudTrashProtocol.trashRequest(file) }
        rejects(details().put("status", "ID_TRASHED").put("size", 124L))
        rejects(details().put("status", "ID_TRASHED").apply { remove("dateModified") })
        rejects(details().put("status", "ID_TRASHED").apply { remove("type") })
    }

    @Test
    fun `trash succeeds only on exact item OK acknowledgement`() {
        ICloudTrashProtocol.verifyTrashResult(trashResponse("OK"), remoteId)
        listOf("ETAG_CONFLICT", "ID_INVALID", "ID_TRASHED", "ERROR", "").forEach { status ->
            assertThrows(ICloudApiException::class.java) {
                ICloudTrashProtocol.verifyTrashResult(trashResponse(status), remoteId)
            }
        }
    }

    @Test
    fun `trash rejects HTTP successful body missing exact item receipt`() {
        listOf(
            "", "not json", "{}", "{\"status\":\"OK\"}", "[]", "{\"items\":[]}",
            "{\"items\":[{\"status\":\"OK\"}]}",
            "{\"items\":[{\"status\":\"OK\",\"drivewsid\":\"other\"}]}",
            JSONObject().put("items", JSONArray().put(details()).put(details())).toString(),
        ).forEach { body ->
            assertThrows(body, ICloudApiException::class.java) {
                ICloudTrashProtocol.verifyTrashResult(body, remoteId)
            }
        }
    }

    @Test
    fun `trash rejects missing item status even with exact identity`() {
        val body = JSONObject().put(
            "items", JSONArray().put(JSONObject().put("drivewsid", remoteId)),
        ).toString()
        assertThrows(ICloudApiException::class.java) { ICloudTrashProtocol.verifyTrashResult(body, remoteId) }
    }

    private fun details(): JSONObject = JSONObject()
        .put("drivewsid", remoteId)
        .put("type", "FILE")
        .put("status", "OK")
        .put("size", 123L)
        .put("dateModified", "2026-08-24T12:30:00Z")
        .put("etag", "current-version")

    private fun verify(item: JSONObject): ICloudTrashProtocol.VerifiedFile = ICloudTrashProtocol.verifyDetails(
        JSONArray().put(item).toString(), remoteId, 123L, modifiedAtMillis,
    )

    private fun rejects(item: JSONObject) {
        assertThrows(ICloudApiException::class.java) { verify(item) }
    }

    private fun trashResponse(status: String): String = JSONObject().put(
        "items", JSONArray().put(JSONObject().put("drivewsid", remoteId).put("status", status)),
    ).toString()
}
