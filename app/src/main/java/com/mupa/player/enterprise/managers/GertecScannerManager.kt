package com.mupa.player.enterprise.managers

import android.content.Context
import android.os.Build
import android.util.Log
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

    companion object {
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
        runCatching {
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
            started = true
            Log.i("MPlayerScan", "gertec_sdk_started device=${Build.DEVICE} model=${Build.MODEL}")
        }.onFailure {
            started = false
            Log.w("MPlayerScan", "gertec_sdk_start_failed err=${it.javaClass.simpleName}:${it.message}")
        }
    }

    fun stop() {
        runCatching {
            codeScanner?.stopService()
        }.onFailure {
            Log.w("MPlayerScan", "gertec_sdk_stop_failed err=${it.message}")
        }
        started = false
    }

    fun isStarted(): Boolean = started
}
