package com.mupa.player.enterprise.kiosk

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.mupa.player.enterprise.managers.SettingsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class KioskModeController(
    private val activity: ComponentActivity,
    private val settingsManager: SettingsManager,
) {
    private var watchdog: Job? = null

    fun applyNow() {
        val enabled = settingsManager.getKioskModeCached() || settingsManager.getMdmLockedCached()
        applyEnabled(enabled)
    }

    fun applyEnabled(enabled: Boolean) {
        if (enabled) {
            DevicePolicyKioskManager(activity).tryEnableLockTask(allowPinningWhenNotDeviceOwner = false)
            hideSystemBars()
            startWatchdog()
        } else {
            stopWatchdog()
            showSystemBars()
        }
    }

    fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = activity.lifecycleScope.launch {
            while (isActive) {
                hideSystemBars()
                delay(900)
            }
        }
    }

    private fun stopWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }
}
