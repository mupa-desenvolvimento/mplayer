package com.mupa.player.enterprise.services

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val intent = android.content.Intent(applicationContext, PlayerForegroundService::class.java)
        ContextCompat.startForegroundService(applicationContext, intent)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "mupa_recovery_worker"
    }
}

