package com.mupa.player.enterprise.bridge

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.utils.NetworkInfoProvider
import org.json.JSONObject

class AndroidBridge(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val commands: Commands,
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
    fun showToast(message: String) {
        showToast(context, message)
    }

    companion object {
        fun showToast(context: Context, message: String) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
