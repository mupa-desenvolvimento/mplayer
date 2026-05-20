package com.mupa.player.enterprise

import android.app.Application
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mupa.player.enterprise.services.RecoveryWorker
import java.util.concurrent.TimeUnit

class MupaApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        val workRequest = PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RecoveryWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest,
        )
    }
}
