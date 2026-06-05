package com.mupa.player.enterprise.bridge

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.palette.graphics.Palette
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.utils.NetworkInfoProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class AndroidBridge(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val commands: Commands,
    private val evaluateJs: (String) -> Unit,
) {
    interface Commands {
        fun onCommandReceived(commandJson: String)
        fun reload()
        fun clearCache()
        fun restartApp()
        fun closeApp()
        fun toggleKiosk(enabled: Boolean)
        fun hideSystemBars()
        fun showSystemBars()
        fun scanBarcode(formatsCsv: String?)
    }

    @JavascriptInterface
    fun sendCommand(commandJson: String) {
        commands.onCommandReceived(commandJson)
    }

    @JavascriptInterface
    fun receiveCommand(commandJson: String) {
        commands.onCommandReceived(commandJson)
    }

    @JavascriptInterface
    fun confirmExecution(): String = "ok"

    @JavascriptInterface
    fun getDeviceInfo(): String {
        val androidId = getAndroidId()
        val ip = NetworkInfoProvider.getIpAddress(context)
        val battery = getBattery()

        val obj = JSONObject()
        obj.put("device_uuid", settingsManager.getDeviceUuidCached())
        obj.put("android_id", androidId)
        obj.put("ip", ip)
        obj.put("battery", battery)
        obj.put("package", context.packageName)
        return obj.toString()
    }

    @JavascriptInterface
    fun getAndroidId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    }

    @JavascriptInterface
    fun getIp(): String = NetworkInfoProvider.getIpAddress(context)

    @JavascriptInterface
    fun getBattery(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    @JavascriptInterface
    fun getVersion(): String {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return pInfo.versionName ?: ""
    }

    @JavascriptInterface
    fun openApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    @JavascriptInterface
    fun closeApp() {
        commands.closeApp()
    }

    @JavascriptInterface
    fun restartApp() {
        commands.restartApp()
    }

    @JavascriptInterface
    fun clearCache() {
        commands.clearCache()
    }

    @JavascriptInterface
    fun reload() {
        commands.reload()
    }

    @JavascriptInterface
    fun toggleKiosk(enabled: Boolean) {
        commands.toggleKiosk(enabled)
    }

    @JavascriptInterface
    fun hideSystemBars() {
        commands.hideSystemBars()
    }

    @JavascriptInterface
    fun showSystemBars() {
        commands.showSystemBars()
    }

    @JavascriptInterface
    fun scanBarcode(): Boolean {
        commands.scanBarcode(null)
        return true
    }

    @JavascriptInterface
    fun scanBarcodeFormats(formatsCsv: String): Boolean {
        commands.scanBarcode(formatsCsv)
        return true
    }

    @JavascriptInterface
    fun showToast(message: String) {
        showToast(context, message)
    }

    @JavascriptInterface
    fun extractImageColors(imageUrlOrBase64: String, callbackId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bitmap = loadBitmap(imageUrlOrBase64)
                val palette = Palette.from(bitmap).generate()
                
                val colors = JSONObject()
                colors.put("dominant", colorToHex(palette.dominantSwatch?.rgb))
                colors.put("vibrant", colorToHex(palette.vibrantSwatch?.rgb))
                colors.put("lightVibrant", colorToHex(palette.lightVibrantSwatch?.rgb))
                colors.put("darkVibrant", colorToHex(palette.darkVibrantSwatch?.rgb))
                colors.put("muted", colorToHex(palette.mutedSwatch?.rgb))
                colors.put("lightMuted", colorToHex(palette.lightMutedSwatch?.rgb))
                colors.put("darkMuted", colorToHex(palette.darkMutedSwatch?.rgb))
                
                val result = JSONObject()
                result.put("success", true)
                result.put("colors", colors)
                result.put("callbackId", callbackId)
                
                withContext(Dispatchers.Main) {
                    evaluateJs("window.onImageColorsExtracted && window.onImageColorsExtracted(${result.toString()})")
                }
            } catch (e: Exception) {
                val error = JSONObject()
                error.put("success", false)
                error.put("error", e.message)
                error.put("callbackId", callbackId)
                
                withContext(Dispatchers.Main) {
                    evaluateJs("window.onImageColorsExtracted && window.onImageColorsExtracted(${error.toString()})")
                }
            }
        }
    }

    private fun loadBitmap(source: String): Bitmap {
        return if (source.startsWith("data:image")) {
            val base64Data = source.substringAfter("base64,")
            val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } else {
            val url = URL(source)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()
            val input: InputStream = connection.inputStream
            BitmapFactory.decodeStream(input)
        }
    }

    private fun colorToHex(color: Int?): String? {
        return color?.let { String.format("#%06X", 0xFFFFFF and it) }
    }

    companion object {
        fun showToast(context: Context, message: String) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
