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
 * via SDK EasyLayer. O leitor é iniciado em modo contínuo e cada código lido é
 * entregue no callback [onBarcode] (thread do SDK — o chamador decide o post
 * para a main thread).
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

    companion object {
        private const val MAX_RETRIES = 15
        private const val RETRY_DELAY_MS = 2_000L

        fun isGertecDevice(): Boolean {
            val device = Build.DEVICE.orEmpty()
            val manufacturer = Build.MANUFACTURER.orEmpty()
            val model = Build.MODEL.orEmpty()
            return device.contains("SK", ignoreCase = true) ||
                model.startsWith("SK", ignoreCase = true) ||
                manufacturer.contains("gertec", ignoreCase = true)
        }
    }

    fun start(context: Context) {
        if (started) return
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
                        if (code.isNotBlank()) {
                            Log.i("MPlayerScan", "gertec_sdk_scan type=$barcodeType data=$code")
                            onBarcode(code)
                        }
                    }.onFailure {
                        Log.w("MPlayerScan", "gertec_sdk_result_failed err=${it.message}")
                    }
                }

                override fun cancelled(causes: String?) {
                    Log.w("MPlayerScan", "gertec_sdk_cancelled causes=$causes")
                    started = false
                }
            }).also { codeScanner = it }

            // O sample da Gertec passa a Activity diretamente — o SDK depende disso
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
            Log.w("MPlayerScan", "gertec_sdk_start desistiu após $MAX_RETRIES tentativas")
        }
    }

    private fun cancelRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    fun stop() {
        cancelRetry()
        runCatching {
            codeScanner?.stopService()
        }.onFailure {
            Log.w("MPlayerScan", "gertec_sdk_stop_failed err=${it.message}")
        }
        started = false
    }

    fun isStarted(): Boolean = started
}
