package com.mupa.player.enterprise.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mupa.player.enterprise.managers.SettingsManager
import kotlinx.coroutines.launch

class KioskManager(
    private val activity: ComponentActivity,
    private val settingsManager: SettingsManager,
) {
    private val kioskModeController = KioskModeController(activity, settingsManager)
    private val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val deviceOwnerPolicyManager = DeviceOwnerPolicyManager(activity)
    private val devicePolicyKioskManager = DevicePolicyKioskManager(activity)

    fun applyNow() {
        val locked = settingsManager.getMdmLockedCached() || settingsManager.getKioskModeCached()
        val allowed = settingsManager.getAllowedPackagesCached()
        if (locked) {
            deviceOwnerPolicyManager.applyLocked(activity.packageName, allowed)
            tryEnableLockTask(allowPinningWhenNotDeviceOwner = false)
        } else {
            runCatching { activity.stopLockTask() }
            deviceOwnerPolicyManager.applyUnlocked(activity.packageName)
        }
        kioskModeController.applyNow()
    }

    fun enableAggressiveKiosk() {
        activity.lifecycleScope.launch {
            settingsManager.setKioskMode(true)
            settingsManager.setMdmLocked(true)
            val allowed = settingsManager.getAllowedPackagesCached()
            deviceOwnerPolicyManager.applyLocked(activity.packageName, allowed)
            tryEnableLockTask(allowPinningWhenNotDeviceOwner = true)
            kioskModeController.hideSystemBars()
        }
    }

    fun disableKiosk() {
        activity.lifecycleScope.launch {
            settingsManager.setMdmLocked(false)
            settingsManager.setKioskMode(false)
            runCatching { activity.stopLockTask() }
            deviceOwnerPolicyManager.applyUnlocked(activity.packageName)
            kioskModeController.showSystemBars()
        }
    }

    fun isLocked(): Boolean = settingsManager.getMdmLockedCached()

    fun hideSystemBars() = kioskModeController.hideSystemBars()
    fun showSystemBars() = kioskModeController.showSystemBars()

    fun rebootDevice(): Boolean = deviceOwnerPolicyManager.rebootDevice(activity.packageName)
    fun rebootDeviceError(): String? = deviceOwnerPolicyManager.rebootDeviceError(activity.packageName)

    private fun tryEnableLockTask(allowPinningWhenNotDeviceOwner: Boolean) {
        val isDeviceOwner = dpm.isDeviceOwnerApp(activity.packageName)
        if (!isDeviceOwner && !allowPinningWhenNotDeviceOwner) return
        devicePolicyKioskManager.tryEnableLockTask(allowPinningWhenNotDeviceOwner = allowPinningWhenNotDeviceOwner)
    }
}
