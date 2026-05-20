package com.mupa.player.enterprise.webview

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.ByteArrayInputStream

class MupaWebViewClient(
    private val resolveSafeMainFrameUrl: () -> String,
    private val onSetupUrlBlocked: () -> Unit,
    private val onSafeMainFrameLoaded: () -> Unit,
    private val onOfflineDetected: () -> Unit,
    private val onOnlineDetected: () -> Unit,
    private val onRendererCrashed: () -> Unit,
) : WebViewClientCompat() {
    private var assetLoader: WebViewAssetLoader? = null
    private var redirectedToNativeRegistration = false
    private var blockedSetupForCurrentMainFrameLoad = false

    fun attach(context: Context) {
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
            .build()
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (isBlockedSetupUrl(request.url)) {
            if (request.isForMainFrame) {
                blockedSetupForCurrentMainFrameLoad = true
                view.post {
                    if (!redirectedToNativeRegistration) {
                        redirectedToNativeRegistration = true
                        runCatching { view.stopLoading() }
                        onSetupUrlBlocked()
                    }
                }
            }
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        return assetLoader?.shouldInterceptRequest(request.url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (isBlockedSetupUrl(url)) {
            if (request.isForMainFrame) blockedSetupForCurrentMainFrameLoad = true
            if (!redirectedToNativeRegistration) {
                redirectedToNativeRegistration = true
                view.stopLoading()
                onSetupUrlBlocked()
            }
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val v = view ?: return
        blockedSetupForCurrentMainFrameLoad = false
        redirectedToNativeRegistration = false
        val parsed = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        if (isBlockedSetupUrl(parsed)) {
            blockedSetupForCurrentMainFrameLoad = true
            if (!redirectedToNativeRegistration) {
                redirectedToNativeRegistration = true
                v.stopLoading()
                onSetupUrlBlocked()
            }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onOnlineDetected()
        if (!blockedSetupForCurrentMainFrameLoad) {
            redirectedToNativeRegistration = false
            onSafeMainFrameLoaded()
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        val v = view ?: return
        val parsed = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        if (isBlockedSetupUrl(parsed)) {
            if (!redirectedToNativeRegistration) {
                redirectedToNativeRegistration = true
                v.stopLoading()
                onSetupUrlBlocked()
            }
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: androidx.webkit.WebResourceErrorCompat) {
        if (request.isForMainFrame) {
            onOfflineDetected()
        }
        super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame) {
            onOfflineDetected()
        }
        super.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererCrashed()
        return true
    }

    private fun isBlockedSetupUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        if (!(host == "midias.mupa.app" || host.endsWith(".midias.mupa.app"))) return false
        val path = uri.path.orEmpty()
        if (path == "/setup" || path.startsWith("/setup/")) return true

        val frag = uri.fragment?.lowercase().orEmpty().trim()
        if (frag.isNotBlank()) {
            val normalized = frag.trimStart('/')
            if (normalized == "setup" || normalized.startsWith("setup/")) return true
            if (normalized.contains("/setup")) return true
        }

        return false
    }
}
