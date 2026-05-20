package com.mupa.player.enterprise.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.mupa.player.enterprise.ui.LauncherActivity

class DeviceOwnerPolicyManager(context: Context) {
    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(appContext, MupaDeviceAdminReceiver::class.java)

    fun isDeviceOwner(packageName: String): Boolean = dpm.isDeviceOwnerApp(packageName)

    fun applyLocked(packageName: String, lockTaskPackages: Set<String>) {
        if (!isDeviceOwner(packageName)) return

        val normalized = lockTaskPackages.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val finalPackages = (setOf(packageName) + normalized).toTypedArray()
        runCatching { dpm.setLockTaskPackages(admin, finalPackages) }
        applyLauncherAsHome(packageName, enabled = true)
        applyDeviceDefaults()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
            runCatching { dpm.setStatusBarDisabled(admin, true) }
            runCatching { dpm.setKeyguardDisabled(admin, true) }
        }

        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) }
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER) }
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA) }
    }

    fun applyUnlocked(packageName: String) {
        if (!isDeviceOwner(packageName)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dpm.setStatusBarDisabled(admin, false) }
            runCatching { dpm.setKeyguardDisabled(admin, false) }
        }

        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA) }

        applyLauncherAsHome(packageName, enabled = false)
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
    }

    fun rebootDevice(packageName: String): Boolean {
        return rebootDeviceError(packageName) == null
    }

    fun rebootDeviceError(packageName: String): String? {
        if (!isDeviceOwner(packageName)) return "not_device_owner"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return "sdk_too_low"
        return runCatching {
            dpm.reboot(admin)
            null
        }.getOrElse { t ->
            val msg = t.message?.trim().orEmpty()
            if (msg.isBlank()) t::class.java.simpleName else "${t::class.java.simpleName}: $msg"
        }
    }

    private fun applyLauncherAsHome(packageName: String, enabled: Boolean) {
        if (!isDeviceOwner(packageName)) return
        if (!enabled) {
            runCatching { dpm.clearPackagePersistentPreferredActivities(admin, packageName) }
            return
        }

        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val homeActivity = ComponentName(appContext, LauncherActivity::class.java)
        runCatching { dpm.addPersistentPreferredActivity(admin, filter, homeActivity) }
    }

    private fun applyDeviceDefaults() {
        runCatching { dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, "0") }
        runCatching { dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, "255") }
        runCatching { dpm.setSystemSetting(admin, Settings.System.FONT_SCALE, "0.85") }

        val current = appContext.resources.displayMetrics.densityDpi
        val forced = (current * 1.15f).toInt().coerceIn(120, 640)
        runCatching { dpm.setSecureSetting(admin, "display_density_forced", forced.toString()) }
    }
}
