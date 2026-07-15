package com.mupa.player.enterprise.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mupa.player.enterprise.DebugReporter
import com.mupa.player.enterprise.audience.AudienceAnalyticsManager
import com.mupa.player.enterprise.databinding.ActivityCameraTestBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class CameraTestActivity : ComponentActivity() {

    private lateinit var binding: ActivityCameraTestBinding

    private var idleJob: Job? = null
    private var audienceManager: AudienceAnalyticsManager? = null
    private var frameDebugLastMs: Long = 0L
    private var loopDebugLastMs: Long = 0L
    private var lastAudienceFrameAtMs: Long = 0L
    private var lastFaces: Int = 0
    private var lastLooking: Int = 0
    private var audienceStarted: Boolean = false
    private var audienceStartError: String? = null
    private var lastMlEvent: String? = null
    private var lastMlEventAtMs: Long = 0L
    private var lastMlFaces: Int? = null
    private var lastMlRotation: Int? = null
    private var lastMlError: String? = null
    private var lastDetectedAgeRange: String? = null
    private var lastDetectedGender: String? = null
    private var lastDetectedEmotion: String? = null
    private var uiDebugJob: Job? = null

    // ms of continuous face presence required to trigger engage
    private val PRESENCE_REQUIRED_MS = 3_000L

    private val engageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.i("ENGAGE", "[ENGAGE] RETURN_TO_PLAYER")
            startIdleDetection()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startIdleDetection()
            else setStatus("Permissão de câmera necessária")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCameraTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewLogButton.setOnClickListener { }
        binding.startEngageButton.setOnClickListener { }
        binding.startFaceButton.setOnClickListener { }
        binding.topInfoCard.setOnLongClickListener {
            val show = binding.debugBar.visibility != View.VISIBLE
            binding.debugBar.visibility = if (show) View.VISIBLE else View.GONE
            binding.debugText.visibility = if (show) View.VISIBLE else View.GONE
            binding.faceInfoOverlay.visibility = if (show) View.VISIBLE else View.GONE
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startIdleDetection()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        stopIdleDetection()
        super.onStop()
    }

    // ──────────────────────────────────────────────────────────────
    // Idle: face detection via AudienceAnalyticsManager
    // ──────────────────────────────────────────────────────────────

    private fun startIdleDetection() {
        stopIdleDetection()
        setStatus("Ola!\nVamos brincar?")
        binding.faceInfoOverlay.update(emptyList())
        audienceStarted = false
        audienceStartError = null
        lastAudienceFrameAtMs = 0L
        lastFaces = 0
        lastLooking = 0
        lastMlEvent = null
        lastMlFaces = null
        lastMlRotation = null
        lastMlError = null
        lastDetectedAgeRange = null
        lastDetectedEmotion = null
        applyTopCard()

        idleJob = lifecycleScope.launch {
            // #region debug-point P1:camera-idle-start
            DebugReporter.report(
                runId = "pre-fix",
                hypothesisId = "A",
                location = "CameraTestActivity.startIdleDetection",
                msg = "[PRESENCE] start_idle_detection",
                data =
                    mapOf(
                        "cameraPermission" to (ContextCompat.checkSelfPermission(this@CameraTestActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED),
                    ),
            )
            // #endregion

            val mgr = AudienceAnalyticsManager(
                context = applicationContext,
                lifecycleOwner = this@CameraTestActivity,
                deviceId = "",
                contentPlayingProvider = { null },
                playlistProvider = { null },
                onFrame = { frame ->
                    // Update face info overlay on every frame
                    val faces = frame.faces
                    binding.faceInfoOverlay.post { binding.faceInfoOverlay.update(faces) }
                    lastAudienceFrameAtMs = SystemClock.elapsedRealtime()
                    lastFaces = faces.size
                    lastLooking = faces.count { it.isLooking }
                    val primaryFace = faces.firstOrNull()
                    val resolvedAge =
                        primaryFace?.ageRange?.trim().orEmpty().ifBlank { null }
                            ?: resolveAgeRangeFromEstimatedAge(primaryFace?.estimatedAge)
                    if (!resolvedAge.isNullOrBlank()) {
                        lastDetectedAgeRange = resolvedAge
                    }
                    val resolvedGender = formatGenderLabel(primaryFace?.gender)
                    if (!resolvedGender.isNullOrBlank()) {
                        lastDetectedGender = resolvedGender
                    }
                    val resolvedEmotion = primaryFace?.emotion?.trim().orEmpty().ifBlank { null }
                    if (!resolvedEmotion.isNullOrBlank()) {
                        lastDetectedEmotion = resolvedEmotion
                    }
                    runCatching { runOnUiThread { applyTopCard() } }

                    // #region debug-point P2:audience-frame
                    val now = SystemClock.elapsedRealtime()
                    val last = frameDebugLastMs
                    if (now - last >= 1000L) {
                        frameDebugLastMs = now
                        val looking = faces.count { it.isLooking }
                        DebugReporter.report(
                            runId = "pre-fix",
                            hypothesisId = "C",
                            location = "CameraTestActivity.onFrame",
                            msg = "[PRESENCE] audience_frame",
                            data =
                                mapOf(
                                    "faces" to faces.size,
                                    "looking" to looking,
                                ),
                        )
                    }
                    // #endregion
                },
                onDebugEvent = { name, data ->
                    lastMlEvent = name
                    lastMlEventAtMs = SystemClock.elapsedRealtime()
                    lastMlFaces = (data["faces"] as? Int)
                    lastMlRotation = (data["rotation"] as? Int)
                    lastMlError =
                        when (name) {
                            "mlkit_failure" -> "${data["exception"]}:${data["message"]}"
                            else -> null
                        }
                },
            )
            audienceManager = mgr

            // #region debug-point P3:audience-start
            val started =
                runCatching {
                    mgr.startIfPossible(
                        surfaceProvider = binding.previewView.surfaceProvider,
                    )
                }.onFailure { e ->
                    audienceStartError = "${e::class.java.simpleName}: ${e.message ?: ""}"
                    DebugReporter.report(
                        runId = "pre-fix",
                        hypothesisId = "B",
                        location = "CameraTestActivity.startIfPossible",
                        msg = "[PRESENCE] audience_start_failed",
                        data = mapOf("exception" to e::class.java.name, "message" to (e.message ?: "")),
                    )
                }.getOrDefault(false)
            audienceStarted = started
            DebugReporter.report(
                runId = "pre-fix",
                hypothesisId = "B",
                location = "CameraTestActivity.startIfPossible",
                msg = "[PRESENCE] audience_start_result",
                data = mapOf("started" to started),
            )
            // #endregion

            if (!started) {
                // Audience manager not available (no models) — fallback to gesture presence
                audienceManager = null
                runCatching { mgr.stop() }
                startGestureFallback()
                return@launch
            }

            setStatus("Ola!\nVamos brincar?")

            var presenceSince = 0L

            while (true) {
                // Use the last onFrame result via the manager's internal state.
                // We track presence in onFrame via a shared flag updated each tick.
                delay(200L)
                // Presence is determined by the faceInfoOverlay having faces
                // We check via a separate shared timestamp set in onFrame.
                val faces = binding.faceInfoOverlay.currentFaces()
                val hasPresence = faces.isNotEmpty()

                // #region debug-point P4:presence-loop
                val now = SystemClock.elapsedRealtime()
                if (now - loopDebugLastMs >= 1000L) {
                    loopDebugLastMs = now
                    DebugReporter.report(
                        runId = "pre-fix",
                        hypothesisId = "C",
                        location = "CameraTestActivity.presenceLoop",
                        msg = "[PRESENCE] loop_tick",
                        data =
                            mapOf(
                                "faces" to faces.size,
                                "looking" to faces.count { it.isLooking },
                                "hasPresence" to hasPresence,
                                "presenceSince" to presenceSince,
                            ),
                    )
                }
                // #endregion

                if (hasPresence) {
                    if (presenceSince == 0L) {
                        presenceSince = SystemClock.elapsedRealtime()
                        Log.i("ENGAGE", "[ENGAGE] PERSON_DETECTED")
                    }
                    val elapsed = (SystemClock.elapsedRealtime() - presenceSince).coerceAtMost(PRESENCE_REQUIRED_MS)
                    if (elapsed >= PRESENCE_REQUIRED_MS) break
                } else {
                    if (presenceSince != 0L) {
                        presenceSince = 0L
                    }
                }
            }

            stopIdleDetection()
            // #region debug-point P5:presence-trigger
            DebugReporter.report(
                runId = "pre-fix",
                hypothesisId = "C",
                location = "CameraTestActivity.presenceLoop",
                msg = "[PRESENCE] trigger_engage",
            )
            // #endregion
            launchEngageFlow()
        }
    }

    // Fallback when audience models aren't available — use any face/hand via gesture recognizer
    private suspend fun startGestureFallback() {
        val gr = com.mupa.engage.vision.EngageGestureRecognizer(applicationContext)
        var presenceSince = 0L
        val lastHandMs = java.util.concurrent.atomic.AtomicLong(0L)

        val started = runCatching {
            gr.start(
                lifecycleOwner = this,
                surfaceProvider = binding.previewView.surfaceProvider,
                onFingerCount = {},
                onEveryFrame = { fingers ->
                    if (fingers > 0) lastHandMs.set(SystemClock.elapsedRealtime())
                    binding.gestureOverlay.post { binding.gestureOverlay.updateFingers(fingers) }
                },
                onHandLandmarks = { hands ->
                    if (hands.isNotEmpty()) lastHandMs.set(SystemClock.elapsedRealtime())
                    binding.gestureOverlay.post { binding.gestureOverlay.updateHands(hands) }
                },
            )
        }.getOrDefault(false)

        if (!started) { setStatus("Falha ao iniciar câmera"); return }
        setStatus("✋ Levante a mão para interagir")

        while (true) {
            val now = SystemClock.elapsedRealtime()
            val hasHand = lastHandMs.get() > 0L && (now - lastHandMs.get()) < 600L
            if (hasHand) {
                if (presenceSince == 0L) {
                    presenceSince = now
                    Log.i("ENGAGE", "[ENGAGE] PERSON_DETECTED")
                }
                val elapsed = (now - presenceSince).coerceAtMost(PRESENCE_REQUIRED_MS)
                if (elapsed >= PRESENCE_REQUIRED_MS) break
            } else {
                if (presenceSince != 0L) {
                    presenceSince = 0L
                }
            }
            delay(80L)
        }
        runCatching { gr.stop() }
        launchEngageFlow()
    }

    private fun stopIdleDetection() {
        idleJob?.cancel()
        idleJob = null
        uiDebugJob?.cancel()
        uiDebugJob = null
        lifecycleScope.launch {
            runCatching { audienceManager?.stop() }
            audienceManager = null
        }
        binding.faceInfoOverlay.update(emptyList())
    }

    // ──────────────────────────────────────────────────────────────
    // Launch EngageActivity
    // ──────────────────────────────────────────────────────────────

    private fun launchEngageFlow() {
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val questionsUrl =
            File(baseDir, "engage-questions-url.txt")
                .takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val analyticsUrl =
            File(baseDir, "engage-analytics-url.txt")
                .takeIf { it.exists() }?.readText()?.trim().orEmpty()

        val i = Intent()
            .setClassName(this, "com.mupa.engage.ui.EngageActivity")
            .putExtra("engage_volume", 80)
            .putExtra("engage_timeout_seconds", 15)
            .putExtra("engage_questions_url", questionsUrl)
            .putExtra("engage_analytics_url", analyticsUrl)
            .putExtra("engage_analytics_enabled", true)
            .putExtra("engage_age_range", lastDetectedAgeRange.orEmpty())
            .putExtra("engage_gender", lastDetectedGender.orEmpty())
            .putExtra("engage_emotion", lastDetectedEmotion.orEmpty())
            .putExtra("engage_context", "renner")

        engageLauncher.launch(i)
    }

    private fun setStatus(text: String) {
        runCatching { runOnUiThread { binding.statusText.text = text } }
    }

    private fun applyTopCard() {
        binding.ageValueText.text = lastDetectedAgeRange?.takeIf { it.isNotBlank() } ?: "Em analise"
        binding.genderValueText.text = lastDetectedGender?.takeIf { it.isNotBlank() } ?: "Em analise"
        val mood = lastDetectedEmotion?.takeIf { it.isNotBlank() } ?: "Em analise"
        binding.moodValueText.text = mood
    }

    private fun restartFaceDetection() {
        stopIdleDetection()
        setStatus("Reiniciando...")
        lifecycleScope.launch {
            delay(200L)
            startIdleDetection()
        }
    }

    private fun resolveAgeBucket(estimatedAge: Int?, ageRange: String?): String? {
        val age = estimatedAge?.takeIf { it > 0 }
        if (age != null) {
            return when {
                age < 13 -> "Crianca"
                age < 18 -> "Adolescente"
                age < 30 -> "Jovem Adulto"
                age < 60 -> "Adulto"
                else -> "Senior"
            }
        }
        return when (ageRange?.trim()) {
            "0-17" -> "Adolescente"
            "18-24", "25-34" -> "Jovem Adulto"
            "35-44", "45-54" -> "Adulto"
            "55-64", "65+" -> "Senior"
            else -> null
        }
    }

    private fun resolveAgeRangeFromEstimatedAge(estimatedAge: Int?): String? {
        val age = estimatedAge?.takeIf { it > 0 } ?: return null
        return when {
            age < 13 -> "6-12 anos"
            age < 18 -> "13-17 anos"
            age < 25 -> "18-24 anos"
            age < 35 -> "25-34 anos"
            age < 45 -> "35-44 anos"
            age < 55 -> "45-54 anos"
            age < 65 -> "55-64 anos"
            else -> "65+ anos"
        }
    }

    private fun formatGenderLabel(gender: String?): String? {
        return when (gender?.trim()?.lowercase()) {
            "male", "masculino" -> "Masculino"
            "female", "feminino" -> "Feminino"
            else -> gender?.trim()?.takeIf { it.isNotBlank() }
        }
    }
}
