package com.mupa.player.enterprise.managers

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference
import br.com.gertec.gdk.codescanner.CodeScanner
import br.com.gertec.gdk.codescanner.ScannerCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ativa o leitor de código de barras integrado dos terminais Gertec (SK100 etc.)
 * via GerSDK V1.0.6 (EasyLayer Unificada Varejo).
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
 *
 * ## Reescrito em 2026-10-06 — diagnóstico real do time da Gertec
 *
 * Relato de campo (cliente Koch) + análise do log `MPlayerScan:
 * gertec_sdk_arm ciclo=N/20` pelo time da Gertec encontrou a causa raiz do
 * "leitor ligado mas não lê": o ciclo antigo (re-arm fixo a cada ~5,2s,
 * [ARM_GAP_MS] + o antigo `REARM_INTERVAL_MS`) **matava cada tentativa no
 * meio da negociação com o scanner** — dos ~4s disponíveis antes do
 * próximo `stopService()`, ~1,5-1,7s já eram só a enumeração USB do
 * `/dev/ttyACM0`; a troca de dados real (~55 frames por ciclo) nunca
 * terminava a tempo. Pior: como nada cancelava/esperava a tentativa
 * anterior antes de iniciar a próxima, o log mostrou DUAS inicializações
 * simultâneas na mesma porta serial em threads diferentes, e coroutines de
 * ciclos antigos ainda vivas depois do "release" (ex.: `saveConfig`
 * falhando com "Scanner not initialized" ~2s depois do fechamento).
 *
 * Mudanças reais (arquitetura sugerida pela Gertec, adaptada ao estilo
 * deste arquivo):
 * 1. **Uma tentativa de arme por vez** — `armJob` nunca é substituído por
 *    outro enquanto `isActive`; [start] é um no-op se já há um em curso.
 * 2. **Timeout de verdade por tentativa** ([INIT_TIMEOUT_MS], 10s, do
 *    lado do app via `withTimeoutOrNull`) — confirmação via
 *    `codeScanner.isRunning()` (reportado como confiável a partir do
 *    GerSDK v1.0.6; antes "mentia"), em vez de só esperar uma leitura
 *    real acontecer. **Achado em teste ao vivo**: a sobrecarga
 *    `scanCode(Context, ScanConfig, String)` com `ALL_CODE_TYPES` lança
 *    `NullPointerException` interna do SDK instantaneamente (parâmetro
 *    "type") nesta versão — o leitor nem chegava a ligar. Voltou pra
 *    `scanCode(Context)`, a mesma chamada simples do código original.
 * 3. **Backoff exponencial** (2s, 4s, 8s... teto de [MAX_BACKOFF_MS])
 *    entre tentativas que falham, em vez do intervalo fixo antigo — evita
 *    ligar/desligar o módulo físico do scanner ~12x/minuto, que é hostil
 *    com o hardware e nunca dava tempo da inicialização terminar.
 * 4. **Nunca desiste** (pedido original do usuário, "o leitor não pode
 *    desligar nunca") — sem teto de tentativas, só o backoff cresce.
 *
 * ## Ajuste em 2026-10-07 — "funcionava, desativou sozinho depois de parado"
 *
 * Relato de campo real (SK100 4001442606002108): o leitor lia normal e
 * parou sozinho depois de ficar um tempo parado, sem ninguém mexer. Causa:
 * o único chamador de [start] é `PlayerActivity.onResume()`, que nunca
 * mais dispara num kiosk ligado o dia inteiro na mesma tela. Se o SDK
 * cancelar a sessão sozinho DEPOIS de armar com sucesso (idle timeout do
 * módulo físico, soluço de USB — causa exata ainda não confirmada, só o
 * sintoma), [started] virava `false` e nada percebia: o loop de arme
 * daquela tentativa já tinha terminado (`return@launch` após `ok=true`).
 * O item 4 acima só cobria retries DENTRO de uma sequência de arme, não
 * esse caso de "armou, funcionou, morreu sozinho depois". Fix: guarda o
 * `Context` da última chamada de [start] ([lastContextRef]) pra o
 * callback [ScannerCallback.cancelled] poder se re-armar sozinho sempre
 * que isso acontecer, sem depender da Activity notar.
 */
class GertecScannerManager(
    private val onBarcode: (String) -> Unit,
) {
    private var codeScanner: CodeScanner? = null
    @Volatile private var started = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var armJob: Job? = null

    // Preenchido pelo callback cancelled() — null enquanto a tentativa atual
    // ainda não terminou (nem com sucesso nem com falha).
    @Volatile private var lastFailure: String? = null

    // Debounce de duplicados: "1 EAN por vez". O mesmo código dentro desta janela é ignorado.
    @Volatile private var lastCode: String? = null
    @Volatile private var lastCodeAtMs: Long = 0L

    // Achado real de campo (2026-10-07, cliente, SK100 4001442606002108):
    // "estava lendo normal, deixei parado e desativou sozinho". Causa: o
    // único chamador de start() é PlayerActivity.onResume() — que nunca
    // mais dispara num kiosk que fica ligado parado o dia inteiro. Se o
    // SDK cancelar a sessão sozinho DEPOIS de já ter armado com sucesso
    // (idle timeout do módulo físico, soluço de enumeração USB, etc. —
    // ver cancelled() abaixo), `started` virava false e nada percebia:
    // o armJob daquela tentativa já tinha terminado (`return@launch` no
    // ok=true), então não tinha mais loop nenhum rodando pra notar. O
    // item 4 do cabeçalho ("nunca desiste") só cobria retries DENTRO de
    // uma sequência de arme — não esse caso de "armou, funcionou, morreu
    // sozinho depois". Guarda o Context da última chamada de start() pra
    // poder se re-armar sozinho, sem depender da Activity notar.
    @Volatile private var lastContextRef: WeakReference<Context>? = null

    companion object {
        private const val TAG = "MPlayerScan"

        // Tempo máximo pra uma tentativa de arme terminar (sucesso ou falha) antes
        // de desistir DESSA tentativa e tentar de novo com backoff. Medido a partir
        // da abertura da sessão (chamada de scanCode), não de um valor arbitrário —
        // cobre a enumeração USB (~1,5-1,7s) mais a negociação real com o scanner.
        private const val INIT_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 100L
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
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
        if (started || armJob?.isActive == true) return
        val ctxRef = WeakReference(context)
        lastContextRef = ctxRef
        armJob = scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            var attempt = 0
            while (true) {
                if (started) return@launch
                val ctx = ctxRef.get() ?: return@launch // Activity foi embora

                attempt++
                val scanner = runCatching {
                    codeScanner ?: CodeScanner.getInstance(buildCallback()).also { codeScanner = it }
                }.getOrNull()
                if (scanner == null) {
                    Log.w(TAG, "gertec_sdk_getInstance_failed ciclo=$attempt")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }

                // Derruba qualquer sessão anterior antes de tentar de novo — sempre,
                // mesmo na 1ª tentativa (a 1ª sessão do boot às vezes nasce morta).
                runCatching { scanner.stopService() }

                lastFailure = null
                val startedAtMs = SystemClock.elapsedRealtime()
                runCatching {
                    // Achado real em teste ao vivo (2026-10-06, log via USB): a
                    // sobrecarga scanCode(Context, ScanConfig, String) com
                    // CodeScanner.ALL_CODE_TYPES lança NullPointerException
                    // interna do SDK (br.com.gertec.retailnexus.internal.k7.a,
                    // parâmetro "type") INSTANTANEAMENTE (0-3ms), antes de
                    // qualquer tentativa de ligar o hardware — o leitor nem
                    // chegava a acender a luz. Essa sobrecarga nunca foi usada
                    // pelo código original (só scanCode(context), sem config)
                    // — volta pra ela. O timeout agora é só o nosso
                    // (INIT_TIMEOUT_MS via withTimeoutOrNull abaixo), sem
                    // depender de ScanConfig.timeout.
                    scanner.scanCode(ctx)
                }.onFailure {
                    Log.w(TAG, "gertec_sdk_scan_failed ciclo=$attempt err=${it.javaClass.simpleName}:${it.message}")
                    lastFailure = it.message ?: it.javaClass.simpleName
                    codeScanner = null // recria a instância na próxima tentativa
                }

                val ok = if (lastFailure != null) {
                    false
                } else {
                    withTimeoutOrNull(INIT_TIMEOUT_MS) {
                        while (!scanner.isRunning() && lastFailure == null) delay(POLL_INTERVAL_MS)
                        scanner.isRunning()
                    } ?: false
                }
                val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                Log.i(TAG, "gertec_sdk_arm ciclo=$attempt ok=$ok elapsed=${elapsedMs}ms falha=$lastFailure")

                if (ok) {
                    started = true
                    return@launch
                }

                runCatching { scanner.stopService() }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun buildCallback() = object : ScannerCallback {
        override fun result(barcodeType: String?, data: String?) {
            // Uma leitura real confirma que a sessão está viva (além do isRunning()).
            started = true
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
                Log.i(TAG, "gertec_sdk_scan type=$barcodeType data=$code")
                onBarcode(code)
            }.onFailure {
                Log.w(TAG, "gertec_sdk_result_failed err=${it.message}")
            }
        }

        override fun cancelled(causes: String?) {
            Log.w(TAG, "gertec_sdk_cancelled causes=$causes")
            lastFailure = causes ?: "cancelled"
            started = false
            // Re-arma sozinho (ver comentário de lastContextRef acima). Se
            // isto disparou DENTRO de uma tentativa de arme ainda em curso
            // (armJob ainda ativo), start() vira no-op pelo guard normal —
            // só o loop já em andamento continua, sem duplicar. null quando
            // stop() já limpou o Context (parada intencional) — não re-arma.
            val ctx = lastContextRef?.get()
            if (ctx != null) {
                scope.launch {
                    delay(INITIAL_BACKOFF_MS)
                    start(ctx)
                }
            }
        }
    }

    fun stop() {
        armJob?.cancel()
        armJob = null
        lastContextRef = null
        runCatching {
            codeScanner?.stopService()
        }.onFailure {
            Log.w(TAG, "gertec_sdk_stop_failed err=${it.message}")
        }
        started = false
    }

    fun isStarted(): Boolean = started
}
