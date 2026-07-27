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

    // Ativação por CICLO DE RE-ARM.
    //
    // PROBLEMA DO BOOT: no primeiro scanCode() após o app subir, o leitor "liga" mas NÃO escaneia
    // nenhum EAN. Fechar e abrir o app (às vezes MAIS DE UMA VEZ) faz passar a ler. A sequência
    // que ativa é scanCode (sessão nasce morta) -> stopService (derruba a morta) -> scanCode de
    // novo. O isRunning() do SDK MENTE (fica true sem decodificar), então não serve de sinal.
    //
    // Solução: automatizar o "fecha e abre" — re-armar em ciclo (stopService -> GAP -> scanCode)
    // a cada REARM_INTERVAL_MS, até uma LEITURA REAL confirmar (started vira true no callback).
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var verifyRunnable: Runnable? = null
    private var armIteration = 0
    // Evita armar duas vezes em paralelo (onResume pode chamar start() de novo durante o ciclo,
    // quando started ainda é false).
    @Volatile private var arming = false
    // Reservado para desistência explícita (não usado no ciclo — mantido por compatibilidade).
    @Volatile private var gaveUp = false

    // Debounce de duplicados: "1 EAN por vez". O mesmo código dentro desta janela é ignorado.
    @Volatile private var lastCode: String? = null
    @Volatile private var lastCodeAtMs: Long = 0L

    companion object {
        // Nº de ciclos de re-arm (cada ciclo = um "fecha e abre" automático) antes de parar.
        private const val ARM_MAX_CYCLES = 20
        // Intervalo entre ciclos: tempo dado à sessão recém-armada para produzir uma leitura
        // antes de re-armar de novo.
        private const val REARM_INTERVAL_MS = 4_000L
        // Gap entre stopService() e scanCode() dentro de um ciclo — replica o "fechar e abrir".
        private const val ARM_GAP_MS = 1_200L
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
        armIteration = 0
        cancelPending()
        armCycle(WeakReference(context))
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

    // Um ciclo = um "fecha e abre" automático: stopService() -> GAP -> scanCode(). Repetimos em
    // cadeia até uma leitura real confirmar (started vira true no callback) — igual o usuário
    // fecha/abre o app "mais de uma vez" até o leitor pegar. O 1º ciclo cria a sessão (que pode
    // nascer morta no boot); o stopService do ciclo seguinte derruba a morta e o scanCode abre uma
    // nova, e assim por diante conforme o windowscannerservice/serial terminam de subir.
    private fun armCycle(ctxRef: WeakReference<Context>) {
        if (started) { arming = false; return }
        val context = ctxRef.get() ?: run { arming = false; return } // Activity foi embora

        val scanner = runCatching {
            codeScanner ?: CodeScanner.getInstance(buildCallback()).also { codeScanner = it }
        }.getOrNull()
        if (scanner == null) { scheduleNextCycle(ctxRef); return }

        // Derruba a sessão anterior (no boot, a 1ª nasce "morta" sem decodificar).
        runCatching { scanner.stopService() }

        val doScan = Runnable {
            if (started) { arming = false; return@Runnable }
            runCatching {
                // Overload simples scanCode(Activity), igual ao sample oficial do SK100. Assíncrono.
                scanner.scanCode(context)
            }.onFailure {
                Log.w("MPlayerScan", "gertec_sdk_scan_failed ciclo=$armIteration err=${it.javaClass.simpleName}:${it.message}")
                codeScanner = null // recria a instância no próximo ciclo
            }
            armIteration++
            Log.i("MPlayerScan", "gertec_sdk_arm ciclo=$armIteration/$ARM_MAX_CYCLES device=${Build.DEVICE} model=${Build.MODEL}")
            scheduleNextCycle(ctxRef)
        }
        verifyRunnable = doScan
        mainHandler.postDelayed(doScan, ARM_GAP_MS)
    }

    private fun scheduleNextCycle(ctxRef: WeakReference<Context>) {
        if (started) { arming = false; return } // uma leitura real encerrou o ciclo
        if (armIteration < ARM_MAX_CYCLES) {
            val r = Runnable { armCycle(ctxRef) }
            retryRunnable = r
            mainHandler.postDelayed(r, REARM_INTERVAL_MS)
        } else {
            // Esgotou os ciclos: mantém a última sessão ativa (uma leitura ainda pode confirmar).
            // Libera arming pra um futuro onResume poder tentar de novo.
            arming = false
            Log.w("MPlayerScan", "gertec_sdk_arm esgotou $ARM_MAX_CYCLES ciclos — última sessão mantida")
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
