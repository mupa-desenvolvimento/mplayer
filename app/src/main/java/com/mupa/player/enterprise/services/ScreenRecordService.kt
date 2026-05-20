package com.mupa.player.enterprise.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.mupa.player.enterprise.R
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

class ScreenRecordService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 30_000L)
        if (deviceId.isBlank() || resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi
        val scale = min(1f, 640f / width.toFloat())
        val targetWidth = ((width * scale).roundToInt() / 2) * 2
        val targetHeight = ((height * scale).roundToInt() / 2) * 2

        val file = File(cacheDir, "rec_${deviceId}_${System.currentTimeMillis()}.mp4")
        outputFile = file

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = projection

        val r = MediaRecorder()
        recorder = r
        runCatching {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(targetWidth, targetHeight)
            r.setVideoFrameRate(15)
            r.setVideoEncodingBitRate(900_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
        }.onFailure {
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        val surface = r.surface
        val vd = projection.createVirtualDisplay(
            "MPlayerScreenRecord",
            targetWidth,
            targetHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
        virtualDisplay = vd

        runCatching { r.start() }.onFailure {
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        mainHandler.postDelayed({
            stopAndUpload(deviceId)
        }, durationMs)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun stopAndUpload(deviceId: String) {
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        val file = outputFile
        if (file == null || !file.exists()) {
            stopSelf()
            return
        }

        val app = FirebaseApp.initializeApp(this) ?: run {
            stopSelf()
            return
        }

        val storage = FirebaseStorage.getInstance(app)
        val path = "recordings/$deviceId/${file.name}"
        val ref = storage.reference.child(path)
        ref.putFile(Uri.fromFile(file))
            .continueWithTask { ref.downloadUrl }
            .addOnSuccessListener { url ->
                publishRecording(deviceId, downloadUrl = url.toString(), storagePath = path, base64Mp4 = null)
                runCatching { file.delete() }
                stopSelf()
            }
            .addOnFailureListener {
                Thread {
                    val base64 = runCatching {
                        val bytes = file.readBytes()
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }.getOrNull()
                    if (base64 != null) {
                        publishRecording(deviceId, downloadUrl = null, storagePath = null, base64Mp4 = base64)
                    }
                    runCatching { file.delete() }
                    stopSelf()
                }.start()
            }
    }

    private fun publishRecording(
        deviceId: String,
        downloadUrl: String?,
        storagePath: String?,
        base64Mp4: String?,
    ) {
        val app = FirebaseApp.initializeApp(this) ?: return
        val db = FirebaseDatabase.getInstance(app)
        val now = System.currentTimeMillis()
        val payload = hashMapOf<String, Any?>(
            "device_id" to deviceId,
            "download_url" to downloadUrl,
            "storage_path" to storagePath,
            "base64_mp4" to base64Mp4,
            "encoding" to if (base64Mp4 != null) "base64" else "url",
            "created_at" to now,
            "duration_ms" to 30_000L,
        )
        db.getReference("device_recordings").child(deviceId).child("last").setValue(payload)
        db.getReference("device_recordings").child(deviceId).child("items").push().setValue(payload)
    }

    private fun cleanup() {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        outputFile = null
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mupa_logo)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Gravando tela (30s)")
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gravação de tela",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mupa_screen_record"
        private const val NOTIFICATION_ID = 2001

        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
    }
}
