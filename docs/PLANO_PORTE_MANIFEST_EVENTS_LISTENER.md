# Plano de Porte: Monitoramento de Manifesto em Tempo Real via Supabase Realtime

> **Origem**: Branch `mPlayer_x96` (commits `6dc3ae6` e `b5f0ff2`)  
> **Destino**: Branch `main` (flavor comum `com.mupa.player.enterprise`)  
> **Data**: 19/10/2026  
> **Status**: Planejado / Pronto para Execução  

---

## 1. Visão Geral

Atualmente, o flavor padrão do mPlayer (`main`) verifica alterações de campanhas e playlists através de um **polling periódico** a cada 1 hora (com retentativa a cada 10 minutos em caso de falha de conexão). 

Na branch `mPlayer_x96`, foi implementado o `ManifestEventsListener`, um serviço autônomo conectado ao **Supabase Realtime (WebSocket)** que escuta o canal `manifest-events`. Quando um novo manifesto ou campanha é publicado no painel, o Supabase dispara uma mensagem via WebSocket e o mPlayer inicia a sincronização e o download imediato das mídias em segundo plano via `refreshInBackground()`.

Este documento descreve os pré-requisitos, a arquitetura e as etapas necessárias para portar essa funcionalidade para a branch `main`.

---

## 2. Arquitetura e Comunicação

### 2.1. Conexão WebSocket com Supabase Realtime
* **URL**: `wss://iurqddkuihjsmxubibao.supabase.co/realtime/v1/websocket?apikey=<SUPABASE_TOKEN>&vsn=1.0.0`
* **Protocolo**: Phoenix Channels v2 (envelope padrão do Supabase Realtime Broadcast).
* **Canal / Tópico**: `"realtime:manifest-events"`
* **Mensagem de Entrada (Join)**:
  ```json
  {
    "topic": "realtime:manifest-events",
    "event": "phx_join",
    "payload": {
      "config": {
        "broadcast": {
          "self": false
        }
      }
    },
    "ref": "join_manifest_events"
  }
  ```
* **Heartbeat**: Envio periódico a cada 25 segundos (`{"topic": "phoenix", "event": "heartbeat", "payload": {}, "ref": "hb_..."}`) para impedir queda por inatividade em roteadores de loja.
* **Resiliência**: Reconexão exponencial com jitter/backoff entre 3s e 60s em caso de perda de link.

### 2.2. Payload do Evento e Filtragem
Ao receber mensagens no canal, o listener decodifica os campos internos e aplica os filtros:
1. **Filtro por Serial (`serials: string[]`)**: Se preenchido no evento, o reload só é disparado se o número de série do dispositivo constar na lista.
2. **Filtro por Tenant (`tenant_id: string`)**: Se não houver filtro por serial, o reload só é disparado se o `tenant_id` recebido for idêntico ao cadastrado no dispositivo.
3. **Broadcast Global**: Se nenhum filtro for especificado, dispara para todos os dispositivos.

---

## 3. Matriz de Pré-requisitos na `main`

| Componente | Situação na `main` | Ação Necessária |
|---|---|---|
| **OkHttp WebSocket** | Presente (`okhttp:4.12.0`) | Nenhuma (suporte nativo via `okhttp3.WebSocketListener`). |
| **Compatibilidade TLS (Android < 7.1.1)** | Presente (`TlsCompat` em `SupabaseClient.kt`) | Nenhuma (reutilizar `TlsCompat.apply(...)`). |
| **Token Supabase** | Presente (`BuildConfig.SUPABASE_TOKEN`) | Nenhuma (já configurado no `defaultConfig`). |
| **Permissões de Rede** | Presente (`INTERNET`, `ACCESS_NETWORK_STATE`) | Nenhuma. |
| **Cache de Dispositivo** | Presente (`DeviceCache.tenant`) | Nenhuma. |

---

## 4. Alterações nos Arquivos

### 4.1. [Novo Arquivo] `app/src/main/java/com/mupa/player/enterprise/network/ManifestEventsListener.kt`

Criar a classe responsável pelo gerenciamento da conexão WebSocket:

```kotlin
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
                .readTimeout(0, TimeUnit.MILLISECONDS)
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

    private fun handleIncomingMessage(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)
            val topic = root.optString("topic", "")
            val event = root.optString("event", "")

            if (event == "phx_reply") return
            if (topic != CHANNEL_TOPIC) return

            val payloadObj = root.optJSONObject("payload") ?: return
            val innerEvent = payloadObj.optString("event", event)
            val data = payloadObj.optJSONObject("payload") ?: payloadObj

            Log.d(TAG, "Evento recebido no canal manifest-events: innerEvent=$innerEvent data=$data")

            val currentDevice = getDeviceId().trim()
            val currentTenant = getTenantId()?.trim()

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
```

---

### 4.2. [Modificação] `app/src/main/java/com/mupa/player/enterprise/managers/ManifestManager.kt`

Adicionar o método de extração de tenant a partir do JSON baixado:

```kotlin
fun parseTenantId(manifestJson: String): String? {
    val trimmed = manifestJson.trim()
    if (trimmed.isBlank()) return null
    return runCatching {
        val root = JSONObject(trimmed)
        val dev = root.optJSONObject("device")
        dev?.optString("tenant_id")?.takeIf { it.isNotBlank() && it != "null" }
    }.getOrNull()
}
```

---

### 4.3. [Modificação] `app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt`

1. **Declarar campos de controle na classe**:
   ```kotlin
   private var currentTenantId: String? = null
   private var manifestEventsListener: com.mupa.player.enterprise.network.ManifestEventsListener? = null
   ```

2. **Sincronização dinâmica de tenant**:
   ```kotlin
   private suspend fun syncTenantFromManifest(manifestJson: String) {
       val parsedTenant = manifestManager.parseTenantId(manifestJson)
       if (!parsedTenant.isNullOrBlank() && parsedTenant != currentTenantId) {
           Log.i("PlayerActivity", "Tenant atualizado a partir do manifesto: $parsedTenant")
           currentTenantId = parsedTenant
           runCatching {
               val cm = DeviceCacheManager(applicationContext)
               val c = cm.load()
               if (c != null) cm.save(c.copy(tenant = parsedTenant))
           }
       }
   }
   ```
   *Inserir chamada para `syncTenantFromManifest(remote)` dentro de `initialSyncAndPlayback()` e `refreshInBackground()`.*

3. **Inicialização no `startLoop()`**:
   ```kotlin
   val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
   currentTenantId = cache?.tenant?.trim()?.ifBlank { null }

   manifestEventsListener?.stop()
   manifestEventsListener = com.mupa.player.enterprise.network.ManifestEventsListener(
       getDeviceId = { deviceId },
       getTenantId = { currentTenantId },
       onReloadRequested = { reason ->
           Log.i("PlayerActivity", "Recarga de manifesto solicitada via manifest-events ($reason)")
           lifecycleScope.launch {
               try {
                   refreshInBackground()
               } catch (e: Exception) {
                   Log.e("PlayerActivity", "Erro no refreshInBackground via manifest-events", e)
               }
           }
       }
   ).also { it.start() }
   ```

4. **Encerramento no `onDestroy()`**:
   ```kotlin
   override fun onDestroy() {
       manifestEventsListener?.stop()
       manifestEventsListener = null
       // ... demais encerramentos
       super.onDestroy()
   }
   ```

---

## 5. Roteiro de Validação e Testes

1. **Compilação**:
   ```powershell
   .\gradlew assembleModernDebug
   ```
2. **Logcat / Inicialização**:
   Filtrar por `ManifestEventsListener`:
   * Verificar se `WebSocket conectado com sucesso! Entrando no canal manifest-events...` é registrado logo após a inicialização.
   * Verificar se o envio do `phx_join` e os heartbeats periódicos de 25s aparecem sem erros.
3. **Teste de Broadcast**:
   Disparar evento no Supabase Realtime para o canal `manifest-events`:
   ```json
   {
     "event": "reload",
     "payload": {
       "serials": ["<SERIAL_DO_DISPOSITIVO>"],
       "reason": "manual_trigger_test"
     }
   }
   ```
   Verificar no logcat se o evento é aceito e se `refreshInBackground()` é acionado discretamente (com a Pill de sincronização) sem interromper a execução do player.
