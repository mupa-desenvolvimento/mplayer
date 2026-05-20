package com.mupa.player.enterprise.webview

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class CustomWebChromeClient(
    private val activity: ComponentActivity,
    private val onShowToast: (String) -> Unit,
    private val onWebPermissionRequest: (PermissionRequest) -> Unit,
) : WebChromeClient() {
    private var filePickerCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePickerCallback ?: return@registerForActivityResult
            filePickerCallback = null
            val uris = FileChooserParams.parseResult(result.resultCode, result.data)
            cb.onReceiveValue(uris)
        }

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onPermissionRequest(request: PermissionRequest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            onWebPermissionRequest(request)
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, true, false)
    }

    override fun onShowFileChooser(
        webView: android.webkit.WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        filePickerCallback?.onReceiveValue(null)
        filePickerCallback = filePathCallback
        val intent = fileChooserParams?.createIntent()
        return try {
            if (intent == null) {
                filePickerCallback = null
                false
            } else {
                filePickerLauncher.launch(intent)
                true
            }
        } catch (_: ActivityNotFoundException) {
            filePickerCallback = null
            onShowToast("Nenhum seletor de arquivos disponível")
            false
        }
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback

        val decor = activity.window.decorView as ViewGroup
        decor.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        view?.keepScreenOn = true
        onShowToast("Fullscreen")
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        val decor = activity.window.decorView as ViewGroup
        decor.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }
}
