package com.faker1024.icloudsync.core.icloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject

@Singleton
internal class ICloudSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun save(session: ICloudSession, cookies: List<Cookie>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(ICloudSessionJson.encode(session, cookies).toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun load(): StoredICloudSession? {
        val data = preferences.getString(KEY_DATA, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val clearText = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)).toString(Charsets.UTF_8)
            ICloudSessionJson.decode(clearText)
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "icloud_private_session"
        const val KEY_DATA = "encrypted_data"
        const val KEY_IV = "iv"
        const val KEY_ALIAS = "icloud_sync_session_key_v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal data class StoredICloudSession(val session: ICloudSession, val cookies: List<Cookie>)

internal object ICloudSessionJson {
    fun encode(session: ICloudSession, cookies: List<Cookie>): String = JSONObject().apply {
        put("appleId", session.appleId)
        put("sessionToken", session.sessionToken)
        put("scnt", session.scnt)
        put("sessionId", session.sessionId)
        put("accountCountry", session.accountCountry)
        put("trustToken", session.trustToken)
        put("authAttributes", session.authAttributes)
        put("frameId", session.frameId)
        put("driveEndpoint", session.driveEndpoint)
        put("docsEndpoint", session.docsEndpoint)
        put("drivePcsRequired", session.drivePcsRequired)
        put("cookies", JSONArray().apply {
            cookies.forEach { cookie ->
                put(JSONObject().apply {
                    put("name", cookie.name)
                    put("value", cookie.value)
                    put("domain", cookie.domain)
                    put("path", cookie.path)
                    put("expiresAt", cookie.expiresAt)
                    put("secure", cookie.secure)
                    put("httpOnly", cookie.httpOnly)
                    put("hostOnly", cookie.hostOnly)
                })
            }
        })
    }.toString()

    fun decode(value: String): StoredICloudSession {
        val json = JSONObject(value)
        val session = ICloudSession(
            appleId = json.getString("appleId"),
            sessionToken = json.optString("sessionToken"),
            scnt = json.optString("scnt"),
            sessionId = json.optString("sessionId"),
            accountCountry = json.optString("accountCountry"),
            trustToken = json.optString("trustToken"),
            authAttributes = json.optString("authAttributes"),
            frameId = json.getString("frameId"),
            driveEndpoint = json.getString("driveEndpoint"),
            docsEndpoint = json.getString("docsEndpoint"),
            drivePcsRequired = json.optBoolean("drivePcsRequired"),
        )
        val cookieJson = json.optJSONArray("cookies") ?: JSONArray()
        val cookies = buildList {
            for (index in 0 until cookieJson.length()) {
                val item = cookieJson.getJSONObject(index)
                val builder = Cookie.Builder()
                    .name(item.getString("name"))
                    .value(item.getString("value"))
                    .path(item.optString("path", "/"))
                    .expiresAt(item.optLong("expiresAt", Long.MAX_VALUE))
                if (item.optBoolean("hostOnly")) {
                    builder.hostOnlyDomain(item.getString("domain"))
                } else {
                    builder.domain(item.getString("domain"))
                }
                if (item.optBoolean("secure")) builder.secure()
                if (item.optBoolean("httpOnly")) builder.httpOnly()
                add(builder.build())
            }
        }
        return StoredICloudSession(session, cookies)
    }
}
