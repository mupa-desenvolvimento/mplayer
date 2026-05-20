package com.mupa.player.enterprise.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mupa.player.enterprise.R
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.ui.LauncherActivity
import com.mupa.player.enterprise.ui.PlayerActivity

class MupaKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startService(Intent(this, MupaLocalApiService::class.java))
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val settings = SettingsManager(applicationContext)
        val target = if (settings.getKioskModeCached() || settings.getMdmLockedCached()) {
            LauncherActivity::class.java
        } else {
            PlayerActivity::class.java
        }
        val startIntent = Intent(this, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(startIntent) }
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, PlayerActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mupa_logo)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Ativo")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mupa Player",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mupa_player_enterprise"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, MupaKeepAliveService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
