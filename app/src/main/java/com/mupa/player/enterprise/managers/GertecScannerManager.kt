package com.mupa.player.enterprise.managers

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import br.com.gertec.gdk.codescanner.CodeScanner
import br.com.gertec.gdk.codescanner.ScannerCallback

/**
 * Ativa o leitor de código de barras integrado dos terminais Gertec (SK100 etc.)
 * via GerSDK V1.0.4 (EasyLayer Unificada Varejo).
 *
 * Modo CDC: o leitor é iniciado com [CodeScanner.scanCode] passando a Activity —
 * exatamente como o sample oficial do SK100 (CodeScannerSKActivity). Cada código
 * lido é entregue EXCLUSIVAMENTE pelo callback do SDK ([ScannerCallback.result]) —
 * não há wedge de teclado (HID). O chamador (PlayerActivity) suprime a captura por
 * dispatchKeyEvent enquanto este leitor está ativo.
 *
 * "1 EAN por vez": o leitor entrega leituras continuamente, então filtramos leituras
 * repetidas do MESMO código dentro de uma janela curta ([DUP_WINDOW_MS]) — assim cada
 * item apresentado gera UMA captura, sem o "machine-gun" do mesmo EAN.
 *
 * Cada código chega em [onBarcode] na thread do SDK — o chamador decide o post
 * para a main thread.
 */
class GertecScannerManager(
    private val onBarcode: (String) -> Unit,
) {
    private var codeScanner: CodeScanner? = null
    @Volatile private var started = false

    // Retry + verificação de ativação real.
    //
    // PROBLEMA DO BOOT: scanCode(Activity) é ASSÍNCRONO no GerSDK 1.0.4 — ele retorna na hora e
    // só configura/abre a serial do leitor num coroutine em background, marcando isRunning()=true
    // quando conclui. No boot (Argos -> MPlayer), o serviço do scanner (WindowScannerService) e a
    // serial ainda estão subindo, então o scanCode() retorna SEM erro mas a config assíncrona
    // falha e isRunning() fica false — leitor aceso, sem decodificar. Reabrir o app "resolvia"
    // porque aí o serviço já estava pronto.
    //
    // Por isso não marcamos started só porque scanCode() não lançou: confirmamos com isRunning()
    // (sinal nativo do SDK) após VERIFY_DELAY_MS e, se não estiver rodando, rearmamos até ativar.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var verifyRunnable: Runnable? = null
    private var retryAttempt = 0
    // Evita armar duas vezes em paralelo (onResume pode chamar start() de novo durante a
    // janela de verificação, quando started ainda é false).
    @Volatile private var arming = false
    // Em terminais sem leitor compatível (ex.: i9100), o init falha sempre. Depois de esgotar
    // as tentativas, desiste até um stop() explícito — evita re-tentar a cada onResume.
    @Volatile private var gaveUp = false

    // Debounce de duplicados: "1 EAN por vez". O mesmo código dentro desta janela é ignorado.
    @Volatile private var lastCode: String? = null
    @Volatile private var lastCodeAtMs: Long = 0L

    companion object {
        private const val MAX_RETRIES = 15
        private const val RETRY_DELAY_MS = 2_000L
        // Tempo para a config assíncrona do scanCode() concluir antes de checar isRunning().
        private const val VERIFY_DELAY_MS = 3_000L
        private const val DUP_WINDOW_MS = 1_500L

        fun isGertecDevice(): Boolean {
            val device = Build.DEVICE.orEmpty()
            val manufacturer = Build.MANUFACTURER.orEmpty()
            val model = Build.MODEL.orEmpty()
            // O SK100 se reporta como manufacturer=UROVO, model/device=i9100 (o módulo de
            // leitura é um UROVO tsg820 embarcado no terminal Gertec) — por isso incluímos UROVO
            // e i9100 na heurística, além dos prefixos "SK"/"gertec".
            return device.contains("SK", ignoreCase = true) ||
                model.startsWith("SK", ignoreCase = true) ||
                manufacturer.contains("gertec", ignoreCase = true) ||
                manufacturer.contains("urovo", ignoreCase = true) ||
                model.equals("i9100", ignoreCase = true) ||
                device.equals("i9100", ignoreCase = true)
        }
    }

    fun start(context: Context) {
        if (started || arming || gaveUp) return
        arming = true
        retryAttempt = 0
        cancelPending()
        attemptStart(WeakReference(context))
    }

    private fun buildCallback() = object : ScannerCallback {
        override fun result(barcodeType: String?, data: String?) {
            // Uma leitura real confirma que a sessão está viva.
            started = true
            arming = false
            runCatching {
                val code = data?.trim().orEmpty()
                if (code.isBlank()) return@runCatching
                // 1 EAN por vez: ignora repetição do mesmo código na janela de debounce.
                val now = System.currentTimeMillis()
                if (code == lastCode && now - lastCodeAtMs < DUP_WINDOW_MS) {
                    return@runCatching
                }
                lastCode = code
                lastCodeAtMs = now
                Log.i("MPlayerScan", "gertec_sdk_scan type=$barcodeType data=$code")
                onBarcode(code)
            }.onFailure {
                Log.w("MPlayerScan", "gertec_sdk_result_failed err=${it.message}")
            }
        }

        override fun cancelled(causes: String?) {
            Log.w("MPlayerScan", "gertec_sdk_cancelled causes=$causes")
            started = false
        }
    }

    private fun attemptStart(ctxRef: WeakReference<Context>) {
        if (started) { arming = false; return }
        val context = ctxRef.get() ?: run { arming = false; return } // Activity foi embora

        val scanner = runCatching {
            val s = codeScanner ?: CodeScanner.getInstance(buildCallback()).also { codeScanner = it }
            // Em RE-ARM (tentativa > 0) limpamos a sessão morta anterior antes de rearmar. Na
            // PRIMEIRA vez NÃO chamamos stopService — fazer isso antes do 1º scanCode quebra a
            // ativação inicial do SDK.
            if (retryAttempt > 0) runCatching { s.stopService() }
            // Modo CDC: entrega cada leitura pelo callback do SDK. Overload simples scanCode(Activity),
            // igual ao sample oficial do SK100 (CodeScannerSKActivity). scanCode é assíncrono.
            s.scanCode(context)
            s
        }.getOrElse {
            Log.w("MPlayerScan", "gertec_sdk_start_failed try=${retryAttempt + 1} err=${it.javaClass.simpleName}:${it.message}")
            codeScanner = null // força recriar a instância na próxima tentativa
            null
        }

        if (scanner == null) {
            scheduleRetry(ctxRef)
            return
        }

        // scanCode é assíncrono: só consideramos ativo quando isRunning() confirmar. Isso evita o
        // "aceso mas não lê" do boot (config assíncrona ainda não concluiu / falhou).
        val v = Runnable {
            if (started) { arming = false; return@Runnable } // já confirmado por uma leitura
            val running = runCatching { scanner.isRunning() }.getOrDefault(false)
            if (running) {
                started = true
                arming = false
                retryAttempt = 0
                Log.i("MPlayerScan", "gertec_sdk_started device=${Build.DEVICE} model=${Build.MODEL} isRunning=true")
            } else {
                Log.w("MPlayerScan", "gertec_sdk_not_running try=${retryAttempt + 1} (boot: serviço/serial subindo) — rearmando")
                codeScanner = null // recria a instância no rearm
                scheduleRetry(ctxRef)
            }
        }
        verifyRunnable = v
        mainHandler.postDelayed(v, VERIFY_DELAY_MS)
    }

    private fun scheduleRetry(ctxRef: WeakReference<Context>) {
        if (retryAttempt < MAX_RETRIES) {
            retryAttempt++
            val r = Runnable { attemptStart(ctxRef) }
            retryRunnable = r
            mainHandler.postDelayed(r, RETRY_DELAY_MS)
            Log.i("MPlayerScan", "gertec_sdk_retry agendado em ${RETRY_DELAY_MS}ms (tentativa $retryAttempt/$MAX_RETRIES)")
        } else {
            gaveUp = true
            arming = false
            Log.w("MPlayerScan", "gertec_sdk_start desistiu após $MAX_RETRIES tentativas (leitor incompatível?)")
        }
    }

    private fun cancelPending() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        verifyRunnable?.let { mainHandler.removeCallbacks(it) }
        verifyRunnable = null
    }

    fun stop() {
        cancelPending()
        gaveUp = false
        arming = false
        runCatching {
            codeScanner?.stopService()
        }.onFailure {
            Log.w("MPlayerScan", "gertec_sdk_stop_failed err=${it.message}")
        }
        started = false
    }

    fun isStarted(): Boolean = started
}
