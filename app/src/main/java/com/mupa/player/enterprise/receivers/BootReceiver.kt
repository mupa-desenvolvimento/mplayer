package com.mupa.player.enterprise.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.mupa.player.enterprise.argos.ArgosCommandScheduler
import com.mupa.player.enterprise.kiosk.DeviceOwnerPolicyManager
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.services.MupaKeepAliveService
import com.mupa.player.enterprise.ui.SplashActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        MupaKeepAliveService.start(context)
        val appContext = context.applicationContext
        ArgosCommandScheduler.ensurePeriodic(appContext)
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            val settings = SettingsManager(appContext)
            if (settings.getKioskModeCached() || settings.getMdmLockedCached()) {
                val allowed = settings.getAllowedPackagesCached()
                DeviceOwnerPolicyManager(appContext).applyLocked(appContext.packageName, allowed)
            }
            val startIntent = Intent(appContext, SplashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            runCatching { appContext.startActivity(startIntent) }
            pending.finish()
        }, 1000L)
    }
}
