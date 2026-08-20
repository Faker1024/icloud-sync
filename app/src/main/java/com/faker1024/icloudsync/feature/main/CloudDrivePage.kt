package com.faker1024.icloudsync.feature.main

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.faker1024.icloudsync.BuildConfig
import com.faker1024.icloudsync.core.web.CHINA_ICLOUD_DRIVE_URL
import com.faker1024.icloudsync.core.web.CloudDriveDownloads

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudDrivePage(
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var currentHost by remember { mutableStateOf("www.icloud.com.cn") }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                setDownloadListener(null)
                webChromeClient = null
                webViewClient = WebViewClient()
                destroy()
            }
            webView = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            TextButton(onClick = { webView?.goBack() }, enabled = canGoBack) { Text("后退") }
            TextButton(onClick = { webView?.goForward() }, enabled = canGoForward) { Text("前进") }
            TextButton(onClick = { webView?.loadUrl(CHINA_ICLOUD_DRIVE_URL) }) { Text("云盘首页") }
            TextButton(onClick = { webView?.reload() }) { Text("刷新") }
            TextButton(onClick = { openSystemDownloads(webView?.context, onMessage) }) { Text("下载") }
        }
        Text(
            text = "官方页面：$currentHost · 文件保存至 ${CloudDriveDownloads.PUBLIC_DOWNLOAD_PATH}",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress in 0..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply cloudWebView@ {
                    webView = this
                    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                        setSupportMultipleWindows(false)
                    }
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@cloudWebView, true)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val uri = request.url
                            if (isAllowedNavigation(uri)) return false
                            openExternal(context, uri, onMessage)
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            progress = 0
                            updateNavigationState(view, url)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            progress = 100
                            updateNavigationState(view, url)
                        }

                        private fun updateNavigationState(view: WebView, url: String?) {
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                            currentHost = url?.let(Uri::parse)?.host ?: "www.icloud.com.cn"
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        val message = CloudDriveDownloads.enqueue(
                            context = context,
                            url = url,
                            userAgent = userAgent,
                            contentDisposition = contentDisposition,
                            mimeType = mimeType,
                        ).fold(
                            onSuccess = { fileName ->
                                "已开始下载 $fileName，保存至 ${CloudDriveDownloads.PUBLIC_DOWNLOAD_PATH}"
                            },
                            onFailure = { error -> error.message ?: "无法开始下载" },
                        )
                        onMessage(message)
                    }
                    loadUrl(CHINA_ICLOUD_DRIVE_URL)
                }
            },
        )
    }
}

private fun isAllowedNavigation(uri: Uri): Boolean {
    if (uri.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return ALLOWED_NAVIGATION_SUFFIXES.any { host == it || host.endsWith(".$it") }
}

private fun openExternal(context: Context, uri: Uri, onMessage: (String) -> Unit) {
    if (uri.scheme != "https") {
        onMessage("已阻止非 HTTPS 外部链接")
        return
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }.onFailure {
        onMessage("无法打开外部链接")
    }
}

private fun openSystemDownloads(context: Context?, onMessage: (String) -> Unit) {
    if (context == null) return
    runCatching {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
    }.onFailure {
        onMessage("无法打开系统下载列表")
    }
}

private val ALLOWED_NAVIGATION_SUFFIXES = setOf(
    "icloud.com.cn",
    "apple.com",
    "icloud-sandbox.com",
)
