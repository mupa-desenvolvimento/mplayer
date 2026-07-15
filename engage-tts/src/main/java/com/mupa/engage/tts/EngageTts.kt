package com.mupa.engage.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Text-to-speech powered by Microsoft Azure Cognitive Services Speech API.
 *
 * Flow per call to [speak]:
 *   1. Fetch (or reuse cached) bearer token from the STS endpoint.
 *   2. POST SSML to the TTS endpoint, receive MP3 audio bytes.
 *   3. Write to a temp file and play with [MediaPlayer].
 *
 * Token lifetime is 10 minutes; we refresh at 9 minutes to be safe.
 */
class EngageTts(
    context: Context,
    private val subscriptionKey: String = DEFAULT_SUBSCRIPTION_KEY,
    private val region: String = DEFAULT_REGION,
    private val voiceName: String = DEFAULT_VOICE,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Token cache ─────────────────────────────────────────────────────────────
    private var cachedToken: String? = null
    private var tokenExpiresAtMs: Long = 0L

    // Playback state ──────────────────────────────────────────────────────────
    @Volatile private var currentPlayer: MediaPlayer? = null
    private var speakJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Public API (mirrors the old Android-TTS interface — non-suspending)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Synthesize [text] and play it asynchronously.
     * Any in-progress speech is stopped immediately.
     * [volume] is in 0..1 range.
     */
    fun speak(text: String, volume: Float = 1f) {
        speakJob?.cancel()
        stopCurrentPlayback()
        speakJob = scope.launch {
            runCatching { speakInternal(text.trim(), volume.coerceIn(0f, 1f)) }
                .onFailure { Log.w(TAG, "speak_failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** Stop any current playback immediately (does not cancel pending synthesis). */
    fun stop() {
        speakJob?.cancel()
        speakJob = null
        stopCurrentPlayback()
    }

    /** Stop playback and release all resources. */
    fun release() {
        stop()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun speakInternal(text: String, volume: Float) {
        if (text.isBlank()) return

        val token = getOrRefreshToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "token_unavailable — skipping TTS")
            return
        }

        val ssml = buildSsml(text)
        val audioBytes = synthesize(token, ssml)
        if (audioBytes == null || audioBytes.isEmpty()) {
            Log.w(TAG, "synthesis_returned_empty")
            return
        }

        // Write to temp file (unique name to avoid mid-write collisions)
        val tmp = File(appContext.cacheDir, "tts_azure_${System.nanoTime()}.mp3")
        tmp.writeBytes(audioBytes)

        withContext(Dispatchers.Main) {
            stopCurrentPlayback()
            val player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    setDataSource(tmp.absolutePath)
                    setVolume(volume, volume)
                    prepare()
                    setOnCompletionListener { mp ->
                        mp.release()
                        runCatching { tmp.delete() }
                        if (currentPlayer === mp) currentPlayer = null
                    }
                    start()
                }
            }.onFailure { e ->
                Log.w(TAG, "mediaplayer_failed: ${e.message}")
                runCatching { tmp.delete() }
            }.getOrNull()

            currentPlayer = player
        }
    }

    /** Returns a valid bearer token, fetching a fresh one if the cached one has expired. */
    private suspend fun getOrRefreshToken(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (!cached.isNullOrBlank() && now < tokenExpiresAtMs) return@withContext cached

        Log.d(TAG, "fetching_new_token region=$region")
        return@withContext runCatching {
            val conn = URL("https://$region.api.cognitive.microsoft.com/sts/v1.0/issuetoken")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Ocp-Apim-Subscription-Key", subscriptionKey)
            conn.setRequestProperty("Content-Length", "0")
            conn.doOutput = true
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.connect()

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "token_http_error code=$code")
                conn.disconnect()
                return@runCatching null
            }

            val token = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()

            if (token.isBlank()) {
                Log.w(TAG, "token_empty_response")
                return@runCatching null
            }

            cachedToken = token
            tokenExpiresAtMs = now + TOKEN_TTL_MS
            Log.d(TAG, "token_refreshed expires_in=${TOKEN_TTL_MS / 1000}s")
            token
        }.getOrElse { e ->
            Log.w(TAG, "token_request_failed: ${e.message}")
            null
        }
    }

    /** Calls the TTS endpoint and returns MP3 bytes, or null on error. */
    private suspend fun synthesize(token: String, ssml: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/ssml+xml")
            // MP3 @ 24kHz mono — MediaPlayer handles this natively on Android
            conn.setRequestProperty("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            conn.setRequestProperty("User-Agent", "MupaEngage/1.0 Android")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            val body = ssml.toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Length", body.size.toString())
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.w(TAG, "synthesis_http_error code=$code err=$err")
                conn.disconnect()
                return@runCatching null
            }

            val bytes = conn.inputStream.readBytes()
            conn.disconnect()
            Log.d(TAG, "synthesis_ok bytes=${bytes.size}")
            bytes
        }.getOrElse { e ->
            Log.w(TAG, "synthesis_request_failed: ${e.message}")
            null
        }
    }

    /** Wraps [text] in SSML for the configured [voiceName]. */
    private fun buildSsml(text: String): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        // xml:gender is informational when name is set; using Female for FranciscaNeural
        return """<speak version="1.0" xml:lang="pt-BR"><voice xml:lang="pt-BR" xml:gender="Female" name="$voiceName">$escaped</voice></speak>"""
    }

    private fun stopCurrentPlayback() {
        val p = currentPlayer
        currentPlayer = null
        runCatching { p?.stop() }
        runCatching { p?.release() }
    }

    companion object {
        private const val TAG = "EngageTts"

        const val DEFAULT_SUBSCRIPTION_KEY = "9beaf866156a478a9bfac946c05cddde"
        const val DEFAULT_REGION = "brazilsouth"

        /**
         * Neural voices available in pt-BR:
         *   Female: pt-BR-FranciscaNeural, pt-BR-BrendaNeural, pt-BR-LeilaNeural,
         *           pt-BR-LeticiaNeural, pt-BR-ManuelaNeural, pt-BR-YaraNeural
         *   Male:   pt-BR-AntonioNeural, pt-BR-DonatoNeural, pt-BR-FabioNeural,
         *           pt-BR-GiovannaNeural (female), pt-BR-HumbertoNeural, pt-BR-JulioNeural,
         *           pt-BR-NicolauNeural, pt-BR-ValerioNeural
         */
        const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"

        /** Tokens expire in 10 min; refresh at 9 min to avoid edge-case expiry. */
        private const val TOKEN_TTL_MS = 9 * 60 * 1000L
    }
}
