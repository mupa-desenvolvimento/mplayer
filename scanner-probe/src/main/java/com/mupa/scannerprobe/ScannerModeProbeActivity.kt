package com.mupa.scannerprobe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.urovo.scansdk.WindowScanner
import com.urovo.scansdk.api.OutputConfig

/**
 * App isolado (applicationId separado, instala ao lado do MPlayer real sem
 * tocar nele) pra confirmar ao vivo se WindowScanner.initialize()/
 * setOutputInterfaceType ainda falha no SK100 (ver MupaApplication do
 * mplayer principal: ScannerException{1002} já documentado nesse mesmo
 * modelo i9100). Loga cada passo em "MPlayerScanProbe" e mostra na tela.
 */
class ScannerModeProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(tv) })

        val log = StringBuilder()
        fun step(msg: String) {
            Log.i(TAG, msg)
            log.append(msg).append("\n")
            tv.text = log.toString()
        }

        step("probe_start")
        runCatching {
            val ws = WindowScanner.getInstance()
            step("getInstance_ok")
            ws.initialize()
            step("initialize_ok")
            step("isInitialized=${ws.isInitialized()}")
            val before = runCatching { ws.getOutputInterfaceType() }
            step("getOutputInterfaceType_before=${before.getOrNull()} err=${before.exceptionOrNull()?.javaClass?.simpleName}:${before.exceptionOrNull()?.message}")
            val setResult = runCatching { ws.setOutputInterfaceType(OutputConfig.INTERFACE_TYPE_KEYBOARD) }
            step("setOutputInterfaceType_KEYBOARD=${setResult.getOrNull()} err=${setResult.exceptionOrNull()?.javaClass?.simpleName}:${setResult.exceptionOrNull()?.message}")
            val after = runCatching { ws.getOutputInterfaceType() }
            step("getOutputInterfaceType_after=${after.getOrNull()} err=${after.exceptionOrNull()?.javaClass?.simpleName}:${after.exceptionOrNull()?.message}")
            val restore = runCatching { ws.setOutputInterfaceType(OutputConfig.INTERFACE_TYPE_CDC) }
            step("restore_CDC=${restore.getOrNull()} err=${restore.exceptionOrNull()?.message}")
        }.onFailure { e ->
            step("FATAL ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "probe_failed", e)
        }
        step("probe_end")
    }

    companion object {
        private const val TAG = "MPlayerScanProbe"
    }
}
