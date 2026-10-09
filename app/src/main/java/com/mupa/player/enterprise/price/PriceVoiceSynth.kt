package com.mupa.player.enterprise.price

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.network.TlsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Voz Azure (Microsoft Cognitive Services, "TTS do Bing" — ver
 * docs/Request_TTS_BING.txt) com cache compartilhado por toda a frota,
 * pra anunciar preço sem pagar a Azure de novo pra frases repetidas.
 *
 * Pedido do usuário (2026-10-09): "não quero gastar... reaproveitando os
 * preços que ela vai dizer". A síntese de verdade (chamada à Azure +
 * upload pro R2) roda do lado de fora do app, na Edge Function
 * `tts-synthesize` (supabase/functions/tts-synthesize) — a chave da Azure
 * e as credenciais do R2 nunca ficam no APK.
 *
 * 3 níveis de cache, do mais barato pro mais caro:
 *   1. Arquivo local (`filesDir/tts_cache/<hash>.mp3`) — zero rede.
 *   2. Linha já existente em `mplayer_tts_audio_cache` (Supabase REST,
 *      leitura direta) — outro device da frota já gerou esse mesmo texto.
 *   3. Edge Function `tts-synthesize` — ninguém gerou ainda; ela sintetiza
 *      e grava a linha nova pros próximos.
 *
 * Qualquer falha em qualquer nível (sem rede, timeout, Azure fora do ar)
 * cai pra [onFallback] — o chamador decide o que fazer (ex.: usar o
 * TextToSpeech nativo do Android, que nunca falta, só soa mais robótico).
 */
class PriceVoiceSynth(context: Context) {
    private val appContext = context.applicationContext
    private val http: OkHttpClient = TlsCompat.apply(
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS),
    ).build()
    private val cacheDir: File by lazy { File(appContext.filesDir, "tts_cache").apply { mkdirs() } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var currentPlayer: MediaPlayer? = null
    private var speakJob: Job? = null

    /**
     * Sintetiza (ou reusa do cache) e toca [text]. Chama [onFallback] na
     * UI thread sempre que não conseguir tocar por essa via — o chamador
     * decide o que fazer (ex.: usar o TTS nativo do Android).
     */
    fun speak(text: String, voiceName: String = DEFAULT_VOICE, volume: Float = 1f, onFallback: () -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            onFallback()
            return
        }
        speakJob?.cancel()
        stopCurrentPlayback()
        speakJob = scope.launch {
            val played = runCatching { speakInternal(trimmed, voiceName, volume.coerceIn(0f, 1f)) }
                .onFailure { Log.w(TAG, "speak_failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            if (!played) withContext(Dispatchers.Main) { onFallback() }
        }
    }

    /** Para qualquer reprodução em andamento (não cancela um fallback já disparado). */
    fun stop() {
        speakJob?.cancel()
        speakJob = null
        stopCurrentPlayback()
    }

    private suspend fun speakInternal(text: String, voiceName: String, volume: Float): Boolean {
        val hash = sha256Hex("$voiceName|$text")
        val localFile = File(cacheDir, "$hash.mp3")
        if (!localFile.exists() || localFile.length() == 0L) {
            val bytes = fetchAudioBytes(hash, text, voiceName) ?: return false
            runCatching { localFile.writeBytes(bytes) }.onFailure { return playBytesDirect(bytes, volume) }
        }
        return playFile(localFile, volume)
    }

    /** Nível 2 (tabela via REST) e, se não achar, nível 3 (Edge Function). */
    private fun fetchAudioBytes(hash: String, text: String, voiceName: String): ByteArray? {
        resolvePublicUrlFromTable(hash)?.let { url -> downloadBytes(url)?.let { return it } }
        val url = requestSynthesis(text, voiceName) ?: return null
        return downloadBytes(url)
    }

    private fun resolvePublicUrlFromTable(hash: String): String? {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return null
        val url = "$SUPABASE_BASE_URL/rest/v1/mplayer_tts_audio_cache?text_hash=eq.$hash&select=public_url&limit=1"
        val req = Request.Builder()
            .url(url)
            .header("apikey", token)
            .header("Authorization", "Bearer $token")
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val arr = JSONArray(resp.body?.string().orEmpty().ifBlank { "[]" })
                if (arr.length() == 0) null else arr.getJSONObject(0).optString("public_url").takeIf { it.isNotBlank() }
            }
        }.getOrElse { e -> Log.d(TAG, "table_lookup_failed: ${e.message}"); null }
    }

    private fun requestSynthesis(text: String, voiceName: String): String? {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return null
        val body = JSONObject().put("text", text).put("voiceName", voiceName).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(SYNTHESIZE_FUNCTION_URL)
            .header("apikey", token)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "synthesize_http_error code=${resp.code}")
                    return@use null
                }
                JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" }).optString("publicUrl").takeIf { it.isNotBlank() }
            }
        }.getOrElse { e -> Log.w(TAG, "synthesize_request_failed: ${e.message}"); null }
    }

    private fun downloadBytes(url: String): ByteArray? {
        val req = Request.Builder().url(url).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }
        }.getOrElse { e -> Log.w(TAG, "download_failed: ${e.message}"); null }
    }

    private suspend fun playFile(file: File, volume: Float): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            stopCurrentPlayback()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setVolume(volume, volume)
                prepare()
                setOnCompletionListener { mp ->
                    mp.release()
                    if (currentPlayer === mp) currentPlayer = null
                }
                start()
            }
            currentPlayer = player
            true
        }.getOrElse { e ->
            Log.w(TAG, "mediaplayer_failed: ${e.message}")
            runCatching { file.delete() } // arquivo local pode estar corrompido — não deixa travado pra sempre
            false
        }
    }

    /** Só usado se a escrita em disco falhar (ex.: storage cheio) — toca sem persistir o cache local. */
    private suspend fun playBytesDirect(bytes: ByteArray, volume: Float): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val tmp = File.createTempFile("tts_tmp_", ".mp3", appContext.cacheDir)
            tmp.writeBytes(bytes)
            stopCurrentPlayback()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(tmp.absolutePath)
                setVolume(volume, volume)
                prepare()
                setOnCompletionListener { mp -> mp.release(); runCatching { tmp.delete() }; if (currentPlayer === mp) currentPlayer = null }
                start()
            }
            currentPlayer = player
            true
        }.getOrElse { false }
    }

    private fun stopCurrentPlayback() {
        val p = currentPlayer
        currentPlayer = null
        runCatching { p?.stop() }
        runCatching { p?.release() }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "PriceVoiceSynth"
        const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
        private const val SUPABASE_BASE_URL = "https://iurqddkuihjsmxubibao.supabase.co"
        private const val SYNTHESIZE_FUNCTION_URL = "$SUPABASE_BASE_URL/functions/v1/tts-synthesize"
    }
}
