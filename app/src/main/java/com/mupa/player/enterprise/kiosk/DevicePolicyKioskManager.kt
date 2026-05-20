package com.mupa.player.enterprise.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

class DevicePolicyKioskManager(private val activity: Activity) {
    private val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun tryEnableLockTask(allowPinningWhenNotDeviceOwner: Boolean) {
        if (isAlreadyInLockTaskMode()) return

        val isDeviceOwner = dpm.isDeviceOwnerApp(activity.packageName)
        if (isDeviceOwner) {
            val admin = ComponentName(activity, MupaDeviceAdminReceiver::class.java)
            runCatching { dpm.setLockTaskPackages(admin, arrayOf(activity.packageName)) }
            runCatching { activity.startLockTask() }
            return
        }

        if (allowPinningWhenNotDeviceOwner) {
            runCatching { activity.startLockTask() }
        }
    }

    private fun isAlreadyInLockTaskMode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }
}
