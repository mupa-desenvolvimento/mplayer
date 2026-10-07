package com.mupa.player.enterprise.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mupa.player.enterprise.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listener independente via Supabase Realtime (WebSocket) no canal "manifest-events".
 *
 * Funciona de forma totalmente autônoma (sem dependência do ARGOS), mantendo
 * conexão WebSocket permanente com o Supabase e escutando eventos de broadcast.
 * Ao receber notificações de alteração de playlist, campanha ou bloqueio de tenant,
 * aciona o callback para que o mPlayer baixe imediatamente o novo manifesto.
 */
class ManifestEventsListener(
    private val getDeviceId: () -> String,
    private val getTenantId: () -> String?,
    private val onReloadRequested: (reason: String) -> Unit,
) {
    companion object {
        private const val TAG = "ManifestEventsListener"
        private const val CHANNEL_TOPIC = "realtime:manifest-events"
        private const val HEARTBEAT_INTERVAL_MS = 25_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var heartbeatCount = 0L

    private val okHttpClient: OkHttpClient by lazy {
        TlsCompat.apply(
            OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // Sem timeout de leitura para manter WebSocket
                .connectTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
        ).build()
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isRunning.get()) {
                sendHeartbeat()
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private val reconnectRunnable = Runnable {
        if (isRunning.get()) {
            Log.d(TAG, "Tentando reconectar WebSocket...")
            connectWebSocket()
        }
    }

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Iniciando listener do canal manifest-events...")
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            connectWebSocket()
            mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Parando listener do canal manifest-events...")
            mainHandler.removeCallbacks(heartbeatRunnable)
            mainHandler.removeCallbacks(reconnectRunnable)
            disconnectWebSocket()
        }
    }

    private fun connectWebSocket() {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) {
            Log.w(TAG, "SUPABASE_TOKEN em branco. Listener não iniciado.")
            return
        }

        disconnectWebSocket()

        val wsUrl = "wss://iurqddkuihjsmxubibao.supabase.co/realtime/v1/websocket?apikey=$token&vsn=1.0.0"
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
    }

    private fun disconnectWebSocket() {
        runCatching {
            webSocket?.close(1000, "Normal closure")
        }
        webSocket = null
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket conectado com sucesso! Entrando no canal manifest-events...")
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                joinChannel(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket fechado (code=$code, reason=$reason)")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Falha na conexão WebSocket: ${t.message}")
                scheduleReconnect()
            }
        }
    }

    private fun joinChannel(ws: WebSocket) {
        // Formato Phoenix Channel join para Supabase Realtime Broadcast
        val joinPayload = JSONObject().apply {
            put("topic", CHANNEL_TOPIC)
            put("event", "phx_join")
            put("payload", JSONObject().apply {
                put("config", JSONObject().apply {
                    put("broadcast", JSONObject().apply {
                        put("self", false)
                    })
                })
            })
            put("ref", "join_manifest_events")
        }

        ws.send(joinPayload.toString())
        Log.d(TAG, "phx_join enviado para $CHANNEL_TOPIC")
    }

    private fun sendHeartbeat() {
        val ws = webSocket ?: return
        heartbeatCount++
        val hb = JSONObject().apply {
            put("topic", "phoenix")
            put("event", "heartbeat")
            put("payload", JSONObject())
            put("ref", "hb_$heartbeatCount")
        }
        ws.send(hb.toString())
    }

    private fun scheduleReconnect() {
        if (!isRunning.get()) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, reconnectDelayMs)
        Log.d(TAG, "Reconexão agendada em ${reconnectDelayMs / 1000}s")
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    /**
     * Processa a mensagem Phoenix recebida.
     * Trata o envelope de broadcast do Supabase Realtime.
     */
    private fun handleIncomingMessage(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)
            val topic = root.optString("topic", "")
            val event = root.optString("event", "")

            // Se for reply de join ou heartbeat, apenas ignora
            if (event == "phx_reply") return

            if (topic != CHANNEL_TOPIC) return

            // No Supabase Realtime v2 Broadcast, o evento pode vir como:
            // "event": "broadcast" com payload contendo { "type": "broadcast", "event": "reload", "payload": { ... } }
            // ou diretamente o evento
            val payloadObj = root.optJSONObject("payload") ?: return
            val innerEvent = payloadObj.optString("event", event)
            val data = payloadObj.optJSONObject("payload") ?: payloadObj

            Log.d(TAG, "Evento recebido no canal manifest-events: innerEvent=$innerEvent data=$data")

            val currentDevice = getDeviceId().trim()
            val currentTenant = getTenantId()?.trim()

            // Filtro por seriais específicos (se informados no evento)
            val targetSerials = data.optJSONArray("serials")
            if (targetSerials != null && targetSerials.length() > 0) {
                var matchesDevice = false
                for (i in 0 until targetSerials.length()) {
                    val s = targetSerials.optString(i, "").trim()
                    if (s.equals(currentDevice, ignoreCase = true)) {
                        matchesDevice = true
                        break
                    }
                }
                if (!matchesDevice) {
                    Log.d(TAG, "Evento ignorado: serial $currentDevice não listado no evento.")
                    return
                }
            } else {
                // Se não há filtro de seriais, verifica se há filtro de tenant
                val targetTenant = data.optString("tenant_id", "").trim()
                if (targetTenant.isNotBlank() && currentTenant != null && currentTenant.isNotBlank()) {
                    if (!targetTenant.equals(currentTenant, ignoreCase = true)) {
                        Log.d(TAG, "Evento ignorado: tenant $targetTenant não coincide com o do dispositivo ($currentTenant).")
                        return
                    }
                }
            }

            val reason = data.optString("reason", "manifest_changed")
            Log.i(TAG, "Notificação de novo manifesto aceita! Motivo: $reason. Disparando reload...")

            mainHandler.post {
                onReloadRequested(reason)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao analisar mensagem WebSocket: ${e.message}")
        }
    }
}
