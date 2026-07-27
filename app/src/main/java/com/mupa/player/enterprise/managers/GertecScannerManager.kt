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

    // Retry: logo após religar o equipamento, o serviço do SDK Gertec pode ainda não estar
    // pronto quando o player chama start() no onResume — sem retry, o leitor não volta a
    // ativar sozinho depois do boot. Tentamos de novo com backoff até o SDK responder.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var retryAttempt = 0
    // Em terminais sem leitor compatível (ex.: i9100), o init falha sempre. Depois de esgotar
    // as tentativas, desiste até um stop() explícito — evita re-tentar a cada onResume.
    @Volatile private var gaveUp = false

    // Debounce de duplicados: "1 EAN por vez". O mesmo código dentro desta janela é ignorado.
    @Volatile private var lastCode: String? = null
    @Volatile private var lastCodeAtMs: Long = 0L

    companion object {
        private const val MAX_RETRIES = 15
        private const val RETRY_DELAY_MS = 2_000L
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
        if (started || gaveUp) return
        retryAttempt = 0
        cancelRetry()
        attemptStart(WeakReference(context))
    }

    private fun attemptStart(ctxRef: WeakReference<Context>) {
        if (started) return
        val context = ctxRef.get() ?: return // Activity foi embora — para de tentar
        val ok = runCatching {
            val scanner = codeScanner ?: CodeScanner.getInstance(object : ScannerCallback {
                override fun result(barcodeType: String?, data: String?) {
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
            }).also { codeScanner = it }

            // Reset limpo antes de armar: se uma sessão anterior ficou meio-aberta (ex.: o app
            // foi morto sem onDestroy), o engine UROVO fica preso segurando a /dev/ttyACM0 e os
            // comandos de config (SCNMOD/KBWENU) dão timeout, travando a leitura no último código.
            // Um stopService() antes do scanCode() libera a serial e garante sessão nova.
            runCatching { scanner.stopService() }
            lastCode = null
            lastCodeAtMs = 0L

            // Modo CDC: entrega cada leitura pelo callback do SDK. Usamos o overload simples
            // scanCode(Activity) — exatamente como o sample oficial do SK100
            // (CodeScannerSKActivity), que é o caminho testado pela Gertec. Aceita 1D e 2D.
            // (Os overloads com ScanConfig/Collection estouravam no SK100 por causa do
            // ALL_CODE_TYPES null e do init do engine UROVO; o simples é o suportado.)
            scanner.scanCode(context)
            true
        }.getOrElse {
            Log.w("MPlayerScan", "gertec_sdk_start_failed try=${retryAttempt + 1} err=${it.javaClass.simpleName}:${it.message}")
            codeScanner = null // força recriar a instância na próxima tentativa
            false
        }

        if (ok) {
            started = true
            retryAttempt = 0
            Log.i("MPlayerScan", "gertec_sdk_started device=${Build.DEVICE} model=${Build.MODEL}")
            return
        }

        // Falhou — reagenda (SDK pode não estar pronto logo após o boot).
        if (retryAttempt < MAX_RETRIES) {
            retryAttempt++
            val r = Runnable { attemptStart(ctxRef) }
            retryRunnable = r
            mainHandler.postDelayed(r, RETRY_DELAY_MS)
            Log.i("MPlayerScan", "gertec_sdk_retry agendado em ${RETRY_DELAY_MS}ms (tentativa $retryAttempt/$MAX_RETRIES)")
        } else {
            gaveUp = true
            Log.w("MPlayerScan", "gertec_sdk_start desistiu após $MAX_RETRIES tentativas (leitor incompatível?)")
        }
    }

    private fun cancelRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    fun stop() {
        cancelRetry()
        gaveUp = false
        runCatching {
            codeScanner?.stopService()
        }.onFailure {
            Log.w("MPlayerScan", "gertec_sdk_stop_failed err=${it.message}")
        }
        started = false
    }

    fun isStarted(): Boolean = started
}
