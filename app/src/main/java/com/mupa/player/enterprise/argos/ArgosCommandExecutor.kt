package com.mupa.player.enterprise.argos

import android.content.Context
import android.content.Intent
import com.mupa.player.enterprise.kiosk.DeviceOwnerPolicyManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.ui.LauncherActivity
import com.mupa.player.enterprise.ui.PlayerActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

data class ExecOutcome(
    val status: String,
    val message: String,
)

class ArgosCommandExecutor(
    private val appContext: Context,
    private val settingsManager: SettingsManager,
) {
    fun execute(deviceId: String, cmd: LocalQueuedCommand): ExecOutcome {
        val params = runCatching { JSONObject(cmd.paramsJson) }.getOrDefault(JSONObject())
        val name = cmd.command.trim()
        return when (name.uppercase()) {
            "KIOSK_ON", "LOCK_TASK_ON", "LOCK_DEVICE" -> {
                runBlocking {
                    settingsManager.setKioskMode(true)
                    settingsManager.setMdmLocked(true)
                }
                val allowed = settingsManager.getAllowedPackagesCached()
                DeviceOwnerPolicyManager(appContext).applyLocked(appContext.packageName, allowed)
                startForegroundUi()
                ExecOutcome("success", "kiosk_enabled")
            }

            "KIOSK_OFF", "LOCK_TASK_OFF", "UNLOCK_DEVICE" -> {
                runBlocking {
                    settingsManager.setMdmLocked(false)
                    settingsManager.setKioskMode(false)
                }
                DeviceOwnerPolicyManager(appContext).applyUnlocked(appContext.packageName)
                startForegroundUi()
                ExecOutcome("success", "kiosk_disabled")
            }

            "SET_KIOSK_APPS", "SET_ALLOWED_APPS", "SET_WHITELIST" -> {
                val arr = params.optJSONArray("packages")
                    ?: params.optJSONArray("allowedPackages")
                    ?: params.optJSONArray("whitelist")
                    ?: JSONArray()
                val pkgs = (0 until arr.length())
                    .mapNotNull { arr.optString(it, "").trim().takeIf { p -> p.isNotBlank() } }
                    .toSet()
                if (pkgs.isEmpty()) return ExecOutcome("failed", "packages_empty")
                runBlocking { settingsManager.setAllowedPackages(pkgs + appContext.packageName) }
                val locked = settingsManager.getMdmLockedCached() || settingsManager.getKioskModeCached()
                if (locked) {
                    DeviceOwnerPolicyManager(appContext).applyLocked(appContext.packageName, settingsManager.getAllowedPackagesCached())
                    startForegroundUi()
                }
                ExecOutcome("success", "allowed_updated")
            }

            "AUTOSTART_SET", "SET_AUTOSTART" -> {
                val pkg = params.optString("package", "").trim()
                if (pkg.isBlank()) return ExecOutcome("failed", "package_empty")
                runBlocking { settingsManager.setAutostartPackage(pkg) }
                ExecOutcome("success", "autostart_set")
            }

            "AUTOSTART_CLEAR", "CLEAR_AUTOSTART" -> {
                runBlocking { settingsManager.setAutostartPackage("") }
                ExecOutcome("success", "autostart_cleared")
            }

            "UPDATE_APP" -> {
                ExecOutcome("failed", "update_not_implemented")
            }

            else -> {
                val compat = JSONObject()
                compat.put("comando", name.lowercase())
                compat.put("timestamp", System.currentTimeMillis())
                if (params.has("pacote")) compat.put("pacote", params.optString("pacote"))
                if (params.has("codbar")) compat.put("codbar", params.optString("codbar"))
                if (params.has("ip_server")) compat.put("ip_server", params.optString("ip_server"))
                if (params.has("url")) compat.put("url", params.optString("url"))

                val ack = com.mupa.player.enterprise.managers.MupaCommandCenter.dispatch(compat.toString(), timeoutMs = 2500)
                if (ack == null) {
                    startForegroundUi()
                    ExecOutcome("timeout", "player_not_ready")
                } else {
                    ExecOutcome(if (ack.status == "success") "success" else "failed", ack.detalhe ?: ack.status)
                }
            }
        }
    }

    private fun startForegroundUi() {
        val locked = settingsManager.getMdmLockedCached() || settingsManager.getKioskModeCached()
        val target = if (locked) LauncherActivity::class.java else PlayerActivity::class.java
        val intent = Intent(appContext, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { appContext.startActivity(intent) }
    }

    companion object {
        fun currentDeviceId(appContext: Context): String {
            return runBlocking { DeviceIdentityManager(appContext).getPersistentId().trim() }
        }
    }
}

