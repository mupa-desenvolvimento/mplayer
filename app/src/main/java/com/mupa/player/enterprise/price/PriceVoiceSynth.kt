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
 * Só UM nível é síncrono — arquivo local (`filesDir/tts_cache/<hash>.mp3`,
 * zero rede, checagem instantânea). Achando o arquivo, toca a voz Azure.
 * NÃO achando, chama [onFallback] NA HORA (sem esperar rede nenhuma —
 * achado real em produção, 2026-10-09: esperar a tabela via REST e depois
 * a Edge Function, em sequência, podia levar até ~26s de silêncio num
 * cache miss com rede ruim/instável, o que parecia o aparelho "travado"
 * pro operador no caixa) e dispara uma busca/síntese em SEGUNDO PLANO
 * (nível 2: tabela `mplayer_tts_audio_cache` via REST; nível 3, se nem
 * isso achar: Edge Function `tts-synthesize`, que sintetiza na Azure e
 * grava a linha nova) só para DEIXAR CACHEADO pra próxima vez — nunca
 * afeta o que já foi falado agora. [onFallback] é o TextToSpeech nativo
 * do Android (grátis, sempre disponível, só soa mais robótico); se ele
 * também não estiver pronto, o chamador simplesmente não fala nada —
 * nunca trava esperando.
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
    // Escopo SEPARADO pro download/síntese em segundo plano — de propósito
    // nunca cancelado por um speak() novo (um scan seguinte não deveria
    // abortar o cache de um produto anterior ainda sendo buscado).
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Dedup: evita disparar N buscas/sínteses em paralelo pro MESMO texto
    // se o operador escanear o mesmo produto novo várias vezes seguidas
    // antes da 1ª busca terminar (cada uma custaria uma síntese Azure).
    private val prefetchInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var currentPlayer: MediaPlayer? = null
    private var speakJob: Job? = null

    /**
     * Toca [text] com a voz Azure SE já estiver cacheada localmente —
     * checagem instantânea, sem rede. Senão, chama [onFallback] NA HORA
     * (nunca espera rede) e dispara uma busca/síntese em segundo plano só
     * pra deixar cacheado pra próxima vez.
     */
    fun speak(text: String, voiceName: String = DEFAULT_VOICE, volume: Float = 1f, onFallback: () -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            onFallback()
            return
        }
        val hash = sha256Hex("$voiceName|$trimmed")
        val localFile = File(cacheDir, "$hash.mp3")
        if (localFile.exists() && localFile.length() > 0L) {
            speakJob?.cancel()
            stopCurrentPlayback()
            speakJob = scope.launch {
                val played = runCatching { playFile(localFile, volume.coerceIn(0f, 1f)) }
                    .onFailure { Log.w(TAG, "play_failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrDefault(false)
                if (!played) withContext(Dispatchers.Main) { onFallback() }
            }
            return
        }
        // Cache miss: fala com o que já tem (nativo) SEM esperar rede nenhuma.
        onFallback()
        prefetchInBackground(hash, trimmed, voiceName, localFile)
    }

    /** Para qualquer reprodução em andamento (não cancela um fallback já disparado nem um prefetch em segundo plano). */
    fun stop() {
        speakJob?.cancel()
        speakJob = null
        stopCurrentPlayback()
    }

    /** Busca (tabela/Edge Function) e grava o arquivo local — fire-and-forget, nunca afeta o que já foi falado. */
    private fun prefetchInBackground(hash: String, text: String, voiceName: String, localFile: File) {
        if (!prefetchInFlight.add(hash)) return // já tem uma busca rodando pro mesmo texto
        prefetchScope.launch {
            try {
                val bytes = fetchAudioBytes(hash, text, voiceName) ?: return@launch
                runCatching { localFile.writeBytes(bytes) }
                    .onFailure { Log.w(TAG, "prefetch_write_failed: ${it.message}") }
            } catch (e: Exception) {
                Log.w(TAG, "prefetch_failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                prefetchInFlight.remove(hash)
            }
        }
    }

    /** Nível 2 (tabela via REST) e, se não achar, nível 3 (Edge Function) — só chamado em segundo plano. */
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
