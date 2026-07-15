package com.mupa.engage.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mupa.engage.analytics.EngageAnalyticsEvent
import com.mupa.engage.analytics.EngageApiClient
import com.mupa.engage.analytics.EngageQuestion
import com.mupa.engage.tts.EngageTts
import com.mupa.engage.ui.databinding.ActivityEngageBinding
import com.mupa.engage.vision.EngageGestureRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.Calendar
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class EngageActivity : ComponentActivity() {

    private lateinit var binding: ActivityEngageBinding

    private var engageVolume: Int = 80
    private var engageTimeoutSeconds: Int = 15
    private var questionsUrl: String? = null
    private var analyticsUrl: String? = null
    private var analyticsEnabled: Boolean = true
    private var ageRange: String? = null
    private var detectedGender: String? = null
    private var detectedEmotion: String? = null
    private var experienceContext: String? = null

    private lateinit var sessionProfile: EngageSessionProfile
    private val debugOkHttp = OkHttpClient()
    private val enableDebugTelemetry = false

    private var tts: EngageTts? = null
    private var gestures: EngageGestureRecognizer? = null

    private var stageJob: Job? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEngageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engageVolume = intent.getIntExtra(EXTRA_VOLUME, 80).coerceIn(0, 100)
        engageTimeoutSeconds = intent.getIntExtra(EXTRA_TIMEOUT_SECONDS, 15).coerceIn(5, 60)
        questionsUrl = intent.getStringExtra(EXTRA_QUESTIONS_URL)?.trim().orEmpty().ifBlank { null }
        analyticsUrl = intent.getStringExtra(EXTRA_ANALYTICS_URL)?.trim().orEmpty().ifBlank { null }
        analyticsEnabled = intent.getBooleanExtra(EXTRA_ANALYTICS_ENABLED, true)
        ageRange = intent.getStringExtra(EXTRA_AGE_RANGE)?.trim().orEmpty().ifBlank { null }
        detectedGender = intent.getStringExtra(EXTRA_GENDER)?.trim().orEmpty().ifBlank { null }
        detectedEmotion = intent.getStringExtra(EXTRA_EMOTION)?.trim().orEmpty().ifBlank { null }
        experienceContext = intent.getStringExtra(EXTRA_CONTEXT)?.trim().orEmpty().ifBlank { null }

        sessionProfile = EngageExperienceEngine.buildProfile(ageRange, detectedEmotion, experienceContext)
        ageRange = EngageExperienceEngine.formatAudienceLabel(sessionProfile.audience) ?: ageRange
        detectedEmotion = EngageExperienceEngine.formatEmotionLabel(sessionProfile.emotion) ?: detectedEmotion

        binding.topInfoCard.visibility = View.GONE

        tts = EngageTts(applicationContext)
        gestures = EngageGestureRecognizer(applicationContext)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            finishCanceled()
            return
        }

        stageJob = lifecycleScope.launch { runFlow() }
    }

    override fun onDestroy() {
        stageJob?.cancel()
        stageJob = null
        runCatching { gestures?.stop() }
        gestures = null
        runCatching { tts?.release() }
        tts = null
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────
    // Main flow state machine
    // ──────────────────────────────────────────────────────────────

    private suspend fun runFlow() {
        val volume = (engageVolume / 100f).coerceIn(0f, 1f)
        val greeting = buildGreeting()
        val welcomeMessage = EngageExperienceEngine.pickWelcomeMessage(sessionProfile)
        applyDetectedInfo()

        log("[ENGAGE] CONSENT")
        showConsent()

        tts?.speak("$greeting. $welcomeMessage", volume = volume)

        val accept = waitForGesture(timeoutMs = 10_000L, stableRequiredMs = 250L)
        log("[ENGAGE] ANSWER decision=$accept stage=consent")

        if (accept == 2 || accept == 0) {
            val exitMessage =
                if (accept == 2) EngageExperienceEngine.pickDeclineMessage()
                else EngageExperienceEngine.pickTimeoutMessage()
            if (accept == 0) {
                log("[ENGAGE] NO_RESPONSE stage=consent")
            }
            tts?.speak(exitMessage, volume = volume)
            showMessageCard(title = "Tudo certo", body = exitMessage, showOptions = false)
            delay(2_000L)
            postAnalytics(participou = false, pergunta = null, resposta = null)
            finishCanceled(if (accept == 0) END_REASON_NO_RESPONSE else END_REASON_DECLINED)
            return
        }

        log("[ENGAGE] ANSWER decision=1 stage=consent")
        showPreparing()
        tts?.speak("Muito obrigado. Preparando uma pergunta para voce.", volume = volume)
        delay(2_000L)

        val questions = loadQuestions(count = 5)
        val total = questions.size.coerceAtLeast(1)

        for ((idx, q) in questions.withIndex()) {
            val n = idx + 1
            log("[ENGAGE] QUESTION id=${q.id} index=$n total=$total")

            val (category, questionText) = splitCategory(q.pergunta)
            showQuestion(
                category = "${category ?: "👗 MODA"}   $n/$total",
                question = questionText,
                option1 = q.opcao1,
                option2 = q.opcao2,
            )

            val answerMs = engageTimeoutSeconds.coerceAtMost(10).toLong() * 1000L
            tts?.speak("$questionText. Opcao 1: ${q.opcao1}. Opcao 2: ${q.opcao2}.", volume = volume)

            val answer = waitForGesture(timeoutMs = answerMs, stableRequiredMs = 450L)
            log("[ENGAGE] ANSWER id=${q.id} decision=$answer")

            if (answer == 0) {
                log("[ENGAGE] NO_RESPONSE stage=question id=${q.id}")
                showMessageCard(
                    title = "Tudo certo",
                    body = "Aproveite sua visita a Renner ❤️",
                    showOptions = false,
                )
                tts?.speak("Tudo certo. Aproveite sua visita a Renner.", volume = volume)
                delay(2_000L)
                finishCanceled(END_REASON_NO_RESPONSE)
                return
            }

            val normalizedAnswer = answer.takeIf { it == 1 || it == 2 }

            if (normalizedAnswer != null) {
                postAnalytics(participou = true, pergunta = q, resposta = normalizedAnswer)
            } else {
                postAnalytics(participou = true, pergunta = q, resposta = null)
            }
            log("ANALYTICS_SAVED questionId=${q.id}")

            val microFeedback = EngageExperienceEngine.pickFeedbackMessage()
            showMessageCard(title = microFeedback, body = "", showOptions = false)
            tts?.speak(microFeedback, volume = volume)
            delay(3_000L)
        }

        val finalMessage = "Obrigado por participar! Aproveite sua visita a Renner."
        showMessageCard(title = "Obrigado!", body = "Aproveite sua visita a Renner ❤️", showOptions = false)
        tts?.speak(finalMessage, volume = volume)
        delay(2_000L)

        log("[ENGAGE] RETURN_TO_PLAYER")
        finishOk()
    }

    // ──────────────────────────────────────────────────────────────
    // Gesture detection — stable for 800ms before accepting
    // ──────────────────────────────────────────────────────────────

    private suspend fun waitForGesture(timeoutMs: Long, stableRequiredMs: Long): Int {
        val g = gestures ?: return 0

        val currentFingers = AtomicInteger(0)
        val stableSinceMs = AtomicLong(0L)
        val stableMs = stableRequiredMs.coerceIn(120L, 1200L)

        val started = runCatching {
            g.start(
                lifecycleOwner = this,
                surfaceProvider = binding.previewView.surfaceProvider,
                onFingerCount = {},
                onEveryFrame = { fingers ->
                    val now = SystemClock.elapsedRealtime()
                    if (fingers != currentFingers.get()) {
                        currentFingers.set(fingers)
                        stableSinceMs.set(if (fingers in 1..2) now else 0L)
                    }
                    binding.gestureOverlay.post {
                        binding.gestureOverlay.updateFingers(fingers)
                    }
                },
                onHandLandmarks = { hands ->
                    binding.gestureOverlay.post {
                        binding.gestureOverlay.updateHands(hands)
                    }
                },
            )
        }.getOrDefault(false)

        if (!started) return 0

        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1_000L)

        while (SystemClock.elapsedRealtime() < deadline) {
            val f = currentFingers.get()
            val since = stableSinceMs.get()
            if (f in 1..2 && since > 0L &&
                SystemClock.elapsedRealtime() - since >= stableMs
            ) break
            delay(40L)
        }

        val decision = run {
            val f = currentFingers.get()
            val since = stableSinceMs.get()
            if (f in 1..2 && since > 0L &&
                SystemClock.elapsedRealtime() - since >= stableMs
            ) f else 0
        }

        runCatching { g.stop() }
        binding.gestureOverlay.post {
            binding.gestureOverlay.updateFingers(-1)
            binding.gestureOverlay.updateHands(emptyList())
        }
        return decision
    }

    // ──────────────────────────────────────────────────────────────
    // Question loading with fallback
    // ──────────────────────────────────────────────────────────────

    private suspend fun loadQuestion(): EngageQuestion {
        val url = questionsUrl
        val remote =
            if (!url.isNullOrBlank()) {
                runCatching { EngageApiClient().fetchQuestions(url) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        return EngageExperienceEngine.selectQuestion(remote, sessionProfile)
    }

    private suspend fun loadQuestions(count: Int): List<EngageQuestion> {
        val url = questionsUrl
        val remote =
            if (!url.isNullOrBlank()) {
                runCatching { EngageApiClient().fetchQuestions(url) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        return EngageExperienceEngine.selectQuestions(remote, sessionProfile, count)
    }

    // ──────────────────────────────────────────────────────────────
    // Analytics
    // ──────────────────────────────────────────────────────────────

    private suspend fun postAnalytics(
        participou: Boolean,
        pergunta: EngageQuestion?,
        resposta: Int?,
    ) {
        if (!analyticsEnabled) return
        val url =
            analyticsUrl
                ?: questionsUrl
                    ?.substringBeforeLast('/', missingDelimiterValue = "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "$it/analytics" }
                ?: return

        val event = EngageAnalyticsEvent.now(
            faixaEtaria = ageRange,
            emocao = detectedEmotion ?: "neutro",
            participou = participou,
            perguntaId = pergunta?.id,
            resposta = resposta,
        )
        runCatching { EngageApiClient().postAnalytics(url, event) }
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private fun buildGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Bom dia"
            hour < 18 -> "Boa tarde"
            else -> "Boa noite"
        }
    }

    private fun applyDetectedInfo() {
        val age =
            ageRange?.takeIf { it.any { c -> c.isDigit() } }
                ?: EngageExperienceEngine.formatAgeYears(sessionProfile.audience)
                ?: "Em analise"
        val gender = detectedGender ?: "Em analise"
        val mood = EngageExperienceEngine.formatEmotionLabel(sessionProfile.emotion) ?: "Em analise"

        binding.ageValueText.text = age
        binding.genderValueText.text = gender
        binding.moodValueText.text = mood
    }

    private fun log(event: String) {
        val message = if (event.startsWith("[")) event else "[ENGAGE] $event"
        Log.i("ENGAGE", message)
        appendLocalLog(JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("event", message))
    }

    private fun debugReport(
        runId: String,
        hypothesisId: String,
        location: String,
        msg: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        if (!enableDebugTelemetry) return
        val obj =
            JSONObject()
                .put("sessionId", DEBUG_SESSION_ID)
                .put("runId", runId)
                .put("hypothesisId", hypothesisId)
                .put("location", location)
                .put("msg", msg)
                .put("ts", System.currentTimeMillis())
                .put("data", JSONObject(data))

        appendLocalLog(obj)

        val url = readDebugServerUrl() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val req =
                    Request.Builder()
                        .url(url)
                        .post(obj.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                debugOkHttp.newCall(req).execute().use { }
            }
        }
    }

    private fun appendLocalLog(obj: JSONObject) {
        runCatching {
            val baseDir = getExternalFilesDir(null) ?: filesDir
            val dir = File(baseDir, "mplayer_debug").apply { mkdirs() }
            val file = File(dir, "crash.ndjson")
            FileOutputStream(file, true).use { it.write((obj.toString() + "\n").toByteArray()) }
        }
    }

    private fun readDebugServerUrl(): String? {
        return runCatching {
            val baseDir = getExternalFilesDir(null) ?: filesDir
            val f = File(baseDir, URL_FILENAME)
            f.takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { null }
        }.getOrNull()
    }

    private fun showConsent() {
        binding.dimOverlay.visibility = View.GONE
        binding.questionCard.visibility = View.GONE
        binding.preparingCard.visibility = View.GONE

        binding.bottomConsentCard.visibility = View.VISIBLE
        binding.bottomConsentCard.alpha = 0f
        binding.bottomConsentCard.scaleX = 0.96f
        binding.bottomConsentCard.scaleY = 0.96f
        binding.bottomConsentCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L).start()
    }

    private fun showPreparing() {
        binding.bottomConsentCard.visibility = View.GONE
        binding.dimOverlay.visibility = View.GONE
        binding.questionCard.visibility = View.GONE

        binding.preparingCard.visibility = View.VISIBLE
        binding.preparingCard.alpha = 0f
        binding.preparingCard.scaleX = 0.96f
        binding.preparingCard.scaleY = 0.96f
        binding.preparingCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L).start()
    }

    private fun showQuestion(
        category: String,
        question: String,
        option1: String,
        option2: String,
    ) {
        binding.bottomConsentCard.visibility = View.GONE
        binding.preparingCard.visibility = View.GONE
        binding.dimOverlay.visibility = View.VISIBLE

        binding.questionCard.visibility = View.VISIBLE
        binding.questionCategoryText.text = category
        binding.questionText.text = question
        binding.option1Button.visibility = View.VISIBLE
        binding.option2Button.visibility = View.VISIBLE
        binding.questionHintText.visibility = View.VISIBLE
        binding.option1Button.text = "1️⃣ $option1"
        binding.option2Button.text = "2️⃣ $option2"
        binding.questionCard.alpha = 0f
        binding.questionCard.scaleX = 0.96f
        binding.questionCard.scaleY = 0.96f
        binding.questionCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L).start()
    }

    private fun showMessageCard(title: String, body: String, showOptions: Boolean) {
        binding.bottomConsentCard.visibility = View.GONE
        binding.preparingCard.visibility = View.GONE
        binding.dimOverlay.visibility = View.VISIBLE
        binding.questionCard.visibility = View.VISIBLE
        binding.questionCategoryText.text = ""
        binding.questionText.text = if (body.isBlank()) title else "$title\n\n$body"
        binding.option1Button.visibility = if (showOptions) View.VISIBLE else View.GONE
        binding.option2Button.visibility = if (showOptions) View.VISIBLE else View.GONE
        binding.questionHintText.visibility = View.GONE
        binding.questionCard.alpha = 0f
        binding.questionCard.scaleX = 0.96f
        binding.questionCard.scaleY = 0.96f
        binding.questionCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).start()
    }

    private fun splitCategory(raw: String): Pair<String?, String> {
        val t = raw.trim()
        val idx = t.indexOf('\n')
        if (idx <= 0) return null to t
        val first = t.substring(0, idx).trim()
        val rest = t.substring(idx + 1).trim()
        if (first.length in 3..18 && rest.isNotBlank()) return first to rest
        return null to t
    }

    private fun finishOk() {
        if (finished) return
        finished = true
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_PARTICIPATED, true)
                .putExtra(EXTRA_END_REASON, END_REASON_COMPLETED),
        )
        finish()
    }

    private fun finishCanceled(reason: String = END_REASON_CANCELED) {
        if (finished) return
        finished = true
        setResult(
            Activity.RESULT_CANCELED,
            Intent()
                .putExtra(EXTRA_PARTICIPATED, false)
                .putExtra(EXTRA_END_REASON, reason),
        )
        finish()
    }

    companion object {
        private const val DEBUG_SESSION_ID = "engage-first-question-exit"
        private const val URL_FILENAME = "debug-server-url.txt"
        private const val END_REASON_COMPLETED = "completed"
        private const val END_REASON_NO_RESPONSE = "no_response"
        private const val END_REASON_DECLINED = "declined"
        private const val END_REASON_CANCELED = "canceled"

        const val EXTRA_VOLUME = "engage_volume"
        const val EXTRA_TIMEOUT_SECONDS = "engage_timeout_seconds"
        const val EXTRA_QUESTIONS_URL = "engage_questions_url"
        const val EXTRA_ANALYTICS_URL = "engage_analytics_url"
        const val EXTRA_ANALYTICS_ENABLED = "engage_analytics_enabled"
        const val EXTRA_AGE_RANGE = "engage_age_range"
        const val EXTRA_GENDER = "engage_gender"
        const val EXTRA_EMOTION = "engage_emotion"
        const val EXTRA_CONTEXT = "engage_context"
        const val EXTRA_PARTICIPATED = "engage_participated"
        const val EXTRA_END_REASON = "engage_end_reason"
    }
}
