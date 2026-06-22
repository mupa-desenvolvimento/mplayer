package com.mupa.player.enterprise.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class InactivityTimerService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private val checkInactivityRunnable = Runnable {
        reopenMPlayer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        resetTimer()
        setupOverlay()
    }

    private fun setupOverlay() {
        if (Settings.canDrawOverlays(this)) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View(this)
            
            // Set touch listener to intercept outside touches
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    resetTimer()
                }
                false
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                1, 1,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )

            try {
                windowManager?.addView(view, params)
                overlayView = view
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun resetTimer() {
        handler.removeCallbacks(checkInactivityRunnable)
        handler.postDelayed(checkInactivityRunnable, 60000L) // 60 seconds
    }

    private fun reopenMPlayer() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkInactivityRunnable)
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
