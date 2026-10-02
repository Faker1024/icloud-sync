package com.faker1024.icloudsync.core.icloud

import java.io.IOException
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

@Singleton
internal class ICloudApiClient @Inject constructor(
    private val sessionStore: ICloudSessionStore,
) {
    private val cookieJar = ICloudCookieJar()
    private val httpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private var session: ICloudSession? = null

    @Synchronized
    fun restoreSession(): Boolean {
        if (hasUsableActiveSession()) return true
        val stored = sessionStore.load() ?: return false
        return runCatching {
            checkServiceEndpoint(stored.session.driveEndpoint)
            checkServiceEndpoint(stored.session.docsEndpoint)
            session = stored.session
            cookieJar.replace(stored.cookies)
            stored.cookies.isNotEmpty()
        }.getOrElse {
            logout()
            false
        }
    }

    fun beginLogin(accountName: String, password: String): ICloudLoginResult {
        val normalizedAccount = accountName.trim().lowercase(Locale.US)
        require(normalizedAccount.isNotBlank() && password.isNotEmpty()) { "请输入 Apple 账户和密码" }
        cookieJar.clear()
        sessionStore.clear()
        val loginSession = ICloudSession(
            appleId = normalizedAccount,
            frameId = UUID.randomUUID().toString().lowercase(Locale.US),
        )
        session = loginSession

        authStart(loginSession)
        postAuthJson(
            path = "/federate?isRememberMeEnabled=true",
            json = JSONObject().put("accountName", normalizedAccount).put("rememberMe", true),
            accepted = setOf(200),
        )

        val srp = AppleSrp()
        val init = postAuthJson(
            path = "/signin/init",
            json = JSONObject()
                .put("a", Base64.getEncoder().encodeToString(srp.publicValue()))
                .put("accountName", normalizedAccount)
                .put("protocols", JSONArray().put("s2k").put("s2k_fo")),
            accepted = setOf(200),
        )
        val initJson = parseObject(init.body)
        val proofs = try {
            srp.proofs(
                accountName = normalizedAccount,
                password = password,
                salt = Base64.getDecoder().decode(initJson.getString("salt")),
                iterations = initJson.getInt("iteration"),
                protocol = initJson.getString("protocol"),
                serverPublicValue = Base64.getDecoder().decode(initJson.getString("b")),
            )
        } catch (error: IllegalArgumentException) {
            throw ICloudApiException(ICloudError.INVALID_RESPONSE, error.message ?: "登录参数无效")
        }
        val completion = postAuthJson(
            path = "/signin/complete?isRememberMeEnabled=true",
            json = JSONObject()
                .put("accountName", normalizedAccount)
                .put("m1", Base64.getEncoder().encodeToString(proofs.m1))
                .put("m2", Base64.getEncoder().encodeToString(proofs.m2))
                .put("c", initJson.getString("c"))
                .put("rememberMe", true)
                .put(
                    "trustTokens",
                    JSONArray().apply { loginSession.trustToken.takeIf(String::isNotBlank)?.let(::put) },
                ),
            accepted = setOf(200, 409, 412),
        )
        proofs.m1.fill(0)
        proofs.m2.fill(0)

        return when (completion.status) {
            409 -> {
                runCatching { requestTrustedDeviceCode() }
                ICloudLoginResult.NeedsTwoFactor(runCatching(::trustedPhones).getOrDefault(emptyList()))
            }
            412 -> {
                postAuthJson("/repair/complete", JSONObject(), setOf(200))
                accountLogin()
            }
            else -> accountLogin()
        }
    }

    fun requestTrustedDeviceCode() {
        val response = authRequest(
            Request.Builder()
                .url("$AUTH_ENDPOINT/verify/trusteddevice/securitycode")
                .put(ByteArray(0).toRequestBody(null)),
            accepted = setOf(200, 204, 409),
        )
        if (response.status == 409 && response.headers[HEADER_SESSION_TOKEN].isNullOrBlank()) {
            throw ICloudApiException(ICloudError.SERVICE_UNAVAILABLE, "Apple 未能发送验证码，请改用短信或稍后重试")
        }
    }

    fun requestSmsCode(phoneId: Int) {
        postAuthJson(
            path = "/verify/phone",
            method = "PUT",
            json = JSONObject()
                .put("phoneNumber", JSONObject().put("id", phoneId))
                .put("mode", "sms"),
            accepted = setOf(200, 204),
        )
    }

    fun verifyTwoFactor(code: String, phoneId: Int?): ICloudLoginResult {
        require(code.matches(Regex("\\d{6}"))) { "请输入 6 位验证码" }
        val response = if (phoneId == null) {
            postAuthJson(
                path = "/verify/trusteddevice/securitycode",
                json = JSONObject().put("securityCode", JSONObject().put("code", code)),
                accepted = setOf(200, 204, 409),
            )
        } else {
            postAuthJson(
                path = "/verify/phone/securitycode",
                json = JSONObject()
                    .put("securityCode", JSONObject().put("code", code))
                    .put("phoneNumber", JSONObject().put("id", phoneId))
                    .put("mode", "sms"),
                accepted = setOf(200, 204, 409),
            )
        }
        if (response.status == 409 && response.headers[HEADER_SESSION_TOKEN].isNullOrBlank()) {
            throw ICloudApiException(ICloudError.BAD_CODE, "验证码不正确或已过期")
        }
        authRequest(
            Request.Builder().url("$AUTH_ENDPOINT/2sv/trust").get().header("Content-Length", "0"),
            accepted = setOf(200, 204),
        )
        return accountLogin()
    }

    fun needsPcsApproval(): Boolean {
        val active = requireSession()
        return active.drivePcsRequired && !cookieJar.contains(PCS_DOCUMENTS_COOKIE)
    }

    fun pollPcsApproval(): ICloudPcsPollResult {
        val active = requireSession()
        if (!needsPcsApproval()) return ICloudPcsPollResult.APPROVED
        val response = commonJsonRequest(
            url = "$SETUP_ENDPOINT/requestPCS",
            method = "POST",
            json = JSONObject()
                .put("appName", "iclouddrive")
                .put("derivedFromUserAction", true),
        )
        val json = parseObject(response.body)
        sessionStore.save(active, cookieJar.snapshot())
        return ICloudPcsProtocol.evaluate(
            status = json.optString("status"),
            hasDocumentsCookie = cookieJar.contains(PCS_DOCUMENTS_COOKIE),
        )
    }

    fun listFolder(folderId: String): List<ICloudDriveItem> = try {
        listFolderOnce(folderId)
    } catch (error: ICloudApiException) {
        if (error.reason != ICloudError.INVALID_RESPONSE || error.statusCode != 400) throw error
        refreshAccountSession()
        listFolderOnce(folderId)
    }

    private fun listFolderOnce(folderId: String): List<ICloudDriveItem> {
        val active = requireSession()
        ensureDriveSupported(active)
        val requestBody = JSONArray().put(
            JSONObject()
                .put("drivewsid", folderId)
                .put("partialData", false)
                .put("includeHierarchy", false),
        )
        val response = commonJsonRequest(
            url = active.driveEndpoint.trimEnd('/') + "/retrieveItemDetailsInFolders",
            method = "POST",
            json = requestBody,
        )
        return ICloudDriveJson.parseFolder(response.body)
    }

    private fun refreshAccountSession() {
        when (accountLogin()) {
            ICloudLoginResult.Authenticated -> Unit
            ICloudLoginResult.NeedsPcsApproval -> throw ICloudApiException(
                ICloudError.ADVANCED_DATA_PROTECTION,
                "iCloud Drive 需要重新取得设备授权，请在受信任 Apple 设备上批准",
            )
            is ICloudLoginResult.NeedsTwoFactor -> throw ICloudApiException(
                ICloudError.SESSION_EXPIRED,
                "iCloud 登录需要重新验证，请退出后重新登录",
            )
        }
    }

    fun downloadTicket(item: ICloudDriveItem): ICloudDownloadTicket {
        require(!item.isFolder) { "文件夹不能直接下载" }
        val active = requireSession()
        ensureDriveSupported(active)
        val parts = item.id.split("::")
        val zone = parts.getOrNull(1).orEmpty().ifBlank { DEFAULT_ZONE }
        val documentId = parts.getOrNull(2) ?: parts.last()
        val url = active.docsEndpoint.trimEnd('/') + "/ws/$zone/download/by_id"
        val target = url.toHttpUrl().newBuilder().addQueryParameter("document_id", documentId).build()
        val response = commonRequest(Request.Builder().url(target).get(), setOf(200))
        val json = parseObject(response.body)
        val token = json.optJSONObject("data_token") ?: json.optJSONObject("package_token")
            ?: throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 没有返回可用的下载地址")
        val downloadUrl = token.optString("url")
        val parsed = runCatching { downloadUrl.toHttpUrl() }.getOrNull()
            ?: throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 下载地址无效")
        if (parsed.scheme != "https" || !isTrustedAppleHost(parsed.host)) {
            throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 返回了不受信任的下载地址")
        }
        return ICloudDownloadTicket(downloadUrl, cookieJar.headerFor(parsed))
    }

    fun openDownload(item: ICloudDriveItem, requestedOffset: Long = 0L): ICloudDownloadSource {
        val safeOffset = requestedOffset.coerceAtLeast(0L)
        val ticket = downloadTicket(item)
        var target = ticket.url.toHttpUrl()
        var cookieHeader = ticket.cookieHeader
        repeat(MAX_DOWNLOAD_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder()
                .url(target)
                .get()
                .header("Accept", "*/*")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$HOME_ENDPOINT/")
                .apply { if (safeOffset > 0L) header("Range", "bytes=$safeOffset-") }
                .apply { cookieHeader.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }
                .build()
            val response = try {
                httpClient.newCall(request).execute()
            } catch (_: IOException) {
                throw ICloudApiException(ICloudError.NETWORK, "下载文件时无法连接 iCloud")
            }
            extractSessionHeaders(response.headers)
            if (response.code == 330 || response.code in 300..399) {
                val location = response.header("Location")
                response.close()
                if (redirectCount >= MAX_DOWNLOAD_REDIRECTS || location.isNullOrBlank()) {
                    throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 下载跳转次数过多")
                }
                val redirected = target.resolve(location)
                    ?: throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 下载跳转地址无效")
                if (redirected.scheme != "https" || !isTrustedAppleHost(redirected.host)) {
                    throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 下载跳转到了不受信任的地址")
                }
                target = redirected
                cookieHeader = cookieJar.headerFor(redirected)
            } else {
                if (response.code == 416 && safeOffset > 0L) {
                    response.close()
                    return openDownload(item, 0L)
                }
                if (!response.isSuccessful) {
                    val status = response.code
                    response.close()
                    throw httpError(status)
                }
                val body = response.body
                    ?: run {
                        response.close()
                        throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 下载内容为空")
                    }
                val contentRange = parseDownloadContentRange(response.header("Content-Range"))
                val acceptedOffset = when {
                    response.code == 206 -> {
                        if (contentRange?.start != safeOffset) {
                            response.close()
                            throw ICloudApiException(
                                ICloudError.INVALID_RESPONSE,
                                "iCloud 返回了无效的断点续传范围",
                            )
                        }
                        safeOffset
                    }
                    else -> 0L
                }
                val bodyLength = body.contentLength()
                if (
                    response.code == 206 && bodyLength >= 0L && contentRange != null &&
                    contentRange.endInclusive - contentRange.start + 1L != bodyLength
                ) {
                    response.close()
                    throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 断点响应长度不一致")
                }
                val totalLength = contentRange?.totalLength
                    ?: bodyLength.takeIf { it >= 0L }?.let { acceptedOffset + it }
                    ?: -1L
                return ICloudDownloadSource(
                    response = response,
                    contentLength = body.contentLength(),
                    mimeType = body.contentType()?.toString(),
                    startOffset = acceptedOffset,
                    totalLength = totalLength,
                )
            }
        }
        throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 下载地址无效")
    }

    fun logout() {
        session = null
        cookieJar.clear()
        sessionStore.clear()
    }

    private fun authStart(active: ICloudSession) {
        val frameTag = "auth-${active.frameId}"
        val url = "$AUTH_ENDPOINT/authorize/signin".toHttpUrl().newBuilder()
            .addQueryParameter("frame_id", frameTag)
            .addQueryParameter("language", "zh_CN")
            .addQueryParameter("skVersion", "7")
            .addQueryParameter("iframeId", frameTag)
            .addQueryParameter("client_id", WIDGET_KEY)
            .addQueryParameter("redirect_uri", HOME_ENDPOINT)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("response_mode", "web_message")
            .addQueryParameter("state", frameTag)
            .addQueryParameter("authVersion", "latest")
            .build()
        val request = Request.Builder().url(url).get().header("Accept", "*/*").header("User-Agent", USER_AGENT)
        execute(request.build(), setOf(200))
    }

    private fun accountLogin(): ICloudLoginResult {
        val active = requireSession()
        if (active.sessionToken.isBlank()) {
            throw ICloudApiException(ICloudError.INVALID_RESPONSE, "Apple 未返回登录会话令牌")
        }
        val response = commonJsonRequest(
            url = "$SETUP_ENDPOINT/accountLogin",
            method = "POST",
            json = JSONObject()
                .put("accountCountryCode", active.accountCountry)
                .put("dsWebAuthToken", active.sessionToken)
                .put("extended_login", true)
                .put("trustToken", active.trustToken),
        )
        val json = parseObject(response.body)
        json.optString("domainToUse").takeIf(String::isNotBlank)?.let { domain ->
            if (!domain.endsWith(".cn", ignoreCase = true)) {
                throw ICloudApiException(ICloudError.SERVICE_UNAVAILABLE, "此 Apple 账户不属于 iCloud 中国区")
            }
        }
        if (json.optJSONObject("dsInfo")?.optBoolean("isWebAccessAllowed", true) == false) {
            throw ICloudApiException(
                ICloudError.SERVICE_UNAVAILABLE,
                "此账户尚未允许网页访问 iCloud，请先在 Apple 官方入口完成账户确认",
            )
        }
        val services = json.optJSONObject("webservices")
            ?: throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 账户未返回云盘服务")
        val drive = services.optJSONObject("drivews")
            ?: throw ICloudApiException(ICloudError.SERVICE_UNAVAILABLE, "此账户未启用 iCloud Drive")
        val docs = services.optJSONObject("docws")
            ?: throw ICloudApiException(ICloudError.SERVICE_UNAVAILABLE, "此账户未启用 iCloud 文档服务")
        active.driveEndpoint = drive.optString("url")
        active.docsEndpoint = docs.optString("url")
        active.drivePcsRequired = drive.optBoolean("pcsRequired")
        checkServiceEndpoint(active.driveEndpoint)
        checkServiceEndpoint(active.docsEndpoint)
        sessionStore.save(active, cookieJar.snapshot())
        return if (needsPcsApproval()) {
            ICloudLoginResult.NeedsPcsApproval
        } else {
            ICloudLoginResult.Authenticated
        }
    }

    private fun trustedPhones(): List<TrustedPhone> {
        val response = authRequest(
            Request.Builder().url(AUTH_ENDPOINT).get().header("Content-Length", "0"),
            setOf(200),
        )
        var json = parseObject(response.body)
        json.optJSONObject("phoneNumberVerification")?.let { nested ->
            if (!json.has("authenticationType")) json = nested
        }
        val result = mutableListOf<TrustedPhone>()
        val array = json.optJSONArray("trustedPhoneNumbers")
        if (array != null) {
            for (index in 0 until array.length()) parsePhone(array.optJSONObject(index))?.let(result::add)
        }
        if (result.isEmpty()) parsePhone(json.optJSONObject("trustedPhoneNumber"))?.let(result::add)
        return result.distinctBy(TrustedPhone::id)
    }

    private fun parsePhone(json: JSONObject?): TrustedPhone? {
        if (json == null || !json.has("id")) return null
        val label = json.optString("numberWithDialCode").ifBlank { json.optString("obfuscatedNumber") }
            .ifBlank { "受信任号码" }
        return TrustedPhone(json.getInt("id"), label)
    }

    private fun postAuthJson(
        path: String,
        json: JSONObject,
        accepted: Set<Int>,
        method: String = "POST",
    ): ResponseData {
        val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(AUTH_ENDPOINT + path)
        when (method) {
            "PUT" -> builder.put(body)
            else -> builder.post(body)
        }
        return authRequest(builder, accepted)
    }

    private fun authRequest(builder: Request.Builder, accepted: Set<Int>): ResponseData {
        authHeaders().forEach(builder::header)
        return execute(builder.build(), accepted)
    }

    private fun authHeaders(): Map<String, String> {
        val active = requireSession()
        val frameTag = "auth-${active.frameId}"
        return buildMap {
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            put("User-Agent", USER_AGENT)
            put("Origin", AUTH_ORIGIN)
            put("Referer", "$AUTH_ORIGIN/")
            put("X-Apple-Widget-Key", WIDGET_KEY)
            put("X-Apple-OAuth-Client-Id", WIDGET_KEY)
            put("X-Apple-OAuth-Client-Type", "firstPartyAuth")
            put("X-Apple-OAuth-Redirect-URI", HOME_ENDPOINT)
            put("X-Apple-OAuth-Require-Grant-Code", "true")
            put("X-Apple-OAuth-Response-Mode", "web_message")
            put("X-Apple-OAuth-Response-Type", "code")
            put("X-Apple-OAuth-State", frameTag)
            put("X-Apple-Frame-Id", frameTag)
            put("X-Requested-With", "XMLHttpRequest")
            put("X-Apple-Mandate-Security-Upgrade", "0")
            put("X-Apple-I-Require-UE", "true")
            put("X-Apple-I-FD-Client-Info", "{\"U\":\"$USER_AGENT\",\"L\":\"zh-CN\",\"Z\":\"GMT+08:00\",\"V\":\"1.1\",\"F\":\"\"}")
            active.authAttributes.takeIf(String::isNotBlank)?.let { put("X-Apple-Auth-Attributes", it) }
            active.scnt.takeIf(String::isNotBlank)?.let { put("scnt", it) }
            active.sessionId.takeIf(String::isNotBlank)?.let { put("X-Apple-ID-Session-Id", it) }
        }
    }

    private fun commonJsonRequest(url: String, method: String, json: Any): ResponseData {
        val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(url)
        if (method == "POST") builder.post(body) else builder.put(body)
        return commonRequest(builder, setOf(200))
    }

    private fun commonRequest(builder: Request.Builder, accepted: Set<Int>): ResponseData {
        builder
            .header("Content-Type", "application/json")
            .header("Origin", HOME_ENDPOINT)
            .header("Referer", "$HOME_ENDPOINT/")
            .header("User-Agent", USER_AGENT)
        return execute(builder.build(), accepted).also { persistSessionIfReady() }
    }

    private fun hasUsableActiveSession(): Boolean {
        val active = session ?: return false
        if (cookieJar.snapshot().isEmpty()) return false
        return runCatching {
            checkServiceEndpoint(active.driveEndpoint)
            checkServiceEndpoint(active.docsEndpoint)
        }.isSuccess
    }

    private fun persistSessionIfReady() {
        val active = session ?: return
        val cookies = cookieJar.snapshot()
        if (cookies.isEmpty()) return
        if (
            runCatching {
                checkServiceEndpoint(active.driveEndpoint)
                checkServiceEndpoint(active.docsEndpoint)
            }.isFailure
        ) return
        sessionStore.save(active.copy(), cookies)
    }

    private fun execute(request: Request, accepted: Set<Int>): ResponseData {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (error: IOException) {
            throw ICloudApiException(ICloudError.NETWORK, "无法连接 iCloud 中国区，请检查网络")
        }
        return response.use {
            extractSessionHeaders(it.headers)
            val result = ResponseData(it.code, it.headers, it.body?.string().orEmpty())
            if (it.code !in accepted) throw httpError(it.code)
            result
        }
    }

    private fun extractSessionHeaders(headers: Headers) {
        val active = session ?: return
        headers["X-Apple-ID-Account-Country"]?.takeIf(String::isNotBlank)?.let { active.accountCountry = it }
        headers["X-Apple-ID-Session-Id"]?.takeIf(String::isNotBlank)?.let { active.sessionId = it }
        headers[HEADER_SESSION_TOKEN]?.takeIf(String::isNotBlank)?.let { active.sessionToken = it }
        headers["X-Apple-TwoSV-Trust-Token"]?.takeIf(String::isNotBlank)?.let { active.trustToken = it }
        headers["scnt"]?.takeIf(String::isNotBlank)?.let { active.scnt = it }
        headers["X-Apple-Auth-Attributes"]?.takeIf(String::isNotBlank)?.let { active.authAttributes = it }
    }

    private fun httpError(status: Int): ICloudApiException = when (status) {
        401, 421 -> ICloudApiException(ICloudError.SESSION_EXPIRED, "iCloud 登录已过期，请重新登录", status)
        403 -> ICloudApiException(ICloudError.BAD_CREDENTIALS, "Apple 账户或密码不正确", status)
        409 -> ICloudApiException(ICloudError.BAD_CODE, "验证码不正确或已过期", status)
        423 -> ICloudApiException(
            ICloudError.ADVANCED_DATA_PROTECTION,
            "此账户的高级数据保护需要额外设备授权",
            status,
        )
        429 -> ICloudApiException(ICloudError.RATE_LIMITED, "尝试次数过多，请稍后再试", status)
        in 500..599 -> ICloudApiException(ICloudError.SERVICE_UNAVAILABLE, "iCloud 服务暂时不可用", status)
        else -> ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 返回异常状态（$status）", status)
    }

    private fun parseObject(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: Exception) {
        throw ICloudApiException(ICloudError.INVALID_RESPONSE, "无法解析 iCloud 返回的数据")
    }

    private fun requireSession(): ICloudSession = session
        ?: throw ICloudApiException(ICloudError.SESSION_EXPIRED, "请先登录 iCloud")

    private fun ensureDriveSupported(active: ICloudSession) {
        if (active.drivePcsRequired && !cookieJar.contains(PCS_DOCUMENTS_COOKIE)) {
            throw ICloudApiException(
                ICloudError.ADVANCED_DATA_PROTECTION,
                "尚未取得 iCloud Drive 的设备授权，请在受信任 Apple 设备上批准",
            )
        }
    }

    private fun checkServiceEndpoint(value: String) {
        val url = runCatching { value.toHttpUrl() }.getOrNull()
        if (url == null || url.scheme != "https" || !isTrustedAppleHost(url.host)) {
            throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 服务地址无效")
        }
    }

    private data class ResponseData(val status: Int, val headers: Headers, val body: String)

    private companion object {
        const val AUTH_ORIGIN = "https://idmsa.apple.com.cn"
        const val AUTH_ENDPOINT = "$AUTH_ORIGIN/appleauth/auth"
        const val HOME_ENDPOINT = "https://www.icloud.com.cn"
        const val SETUP_ENDPOINT = "https://setup.icloud.com.cn/setup/ws/1"
        const val DEFAULT_ZONE = "com.apple.CloudDocs"
        const val HEADER_SESSION_TOKEN = "X-Apple-Session-Token"
        const val PCS_DOCUMENTS_COOKIE = "X-APPLE-WEBAUTH-PCS-Documents"
        const val MAX_DOWNLOAD_REDIRECTS = 5
        const val WIDGET_KEY = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal data class ICloudDownloadTicket(val url: String, val cookieHeader: String)

internal class ICloudDownloadSource(
    private val response: Response,
    val contentLength: Long,
    val mimeType: String?,
    val startOffset: Long,
    val totalLength: Long,
) : AutoCloseable {
    val inputStream
        get() = checkNotNull(response.body).byteStream()

    override fun close() = response.close()
}

internal data class DownloadContentRange(
    val start: Long,
    val endInclusive: Long,
    val totalLength: Long?,
)

internal fun parseDownloadContentRange(value: String?): DownloadContentRange? {
    val match = DOWNLOAD_CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (start < 0L || end < start || (total != null && (total <= end || total <= 0L))) return null
    return DownloadContentRange(start, end, total)
}

private val DOWNLOAD_CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)

internal object ICloudPcsProtocol {
    fun evaluate(status: String, hasDocumentsCookie: Boolean): ICloudPcsPollResult {
        if (!status.equals("success", ignoreCase = true)) return ICloudPcsPollResult.WAITING
        if (!hasDocumentsCookie) {
            throw ICloudApiException(
                ICloudError.INVALID_RESPONSE,
                "设备已批准，但 iCloud 没有返回云盘解密授权，请重新登录后重试",
            )
        }
        return ICloudPcsPollResult.APPROVED
    }
}

internal object ICloudDriveJson {
    fun parseFolder(body: String): List<ICloudDriveItem> {
        val response = try {
            JSONArray(body)
        } catch (_: Exception) {
            throw ICloudApiException(ICloudError.INVALID_RESPONSE, "无法解析 iCloud 文件列表")
        }
        val folder = response.optJSONObject(0)
            ?: throw ICloudApiException(ICloudError.INVALID_RESPONSE, "iCloud 文件夹不存在")
        val items = folder.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val name = item.optString("name").ifBlank { "未命名文件" }
                val extension = item.optString("extension")
                add(
                    ICloudDriveItem(
                        id = item.optString("drivewsid"),
                        name = if (extension.isNotBlank()) "$name.$extension" else name,
                        type = item.optString("type", "FILE"),
                        size = item.optLong("size", 0L),
                        modifiedAt = item.optString("dateModified").takeIf(String::isNotBlank),
                        childCount = item.optLong("directChildrenCount", item.optLong("fileCount", 0L)),
                    ),
                )
            }
        }.filter { it.id.isNotBlank() }.sortedWith(
            compareByDescending<ICloudDriveItem> { it.isFolder }.thenBy { it.name.lowercase(Locale.getDefault()) },
        )
    }
}

internal fun isTrustedAppleHost(hostValue: String): Boolean {
    val host = hostValue.lowercase(Locale.US)
    return TRUSTED_APPLE_SUFFIXES.any { host == it || host.endsWith(".$it") }
}

private val TRUSTED_APPLE_SUFFIXES = setOf(
    "icloud.com.cn",
    "icloud-content.com.cn",
    "icloud.com",
    "icloud-content.com",
    "apple-cloudkit.com",
    "apzones.com",
    "cdn-apple.com",
    "apple.com.cn",
    "apple.com",
)
