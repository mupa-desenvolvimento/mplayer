package com.mupa.player.enterprise

import android.app.Application
import android.util.Log

class MupaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installScannerCrashGuard()
    }

    /**
     * O SDK do scanner (Gertec EasyLayer / UROVO) inicializa numa coroutine INTERNA dele.
     * Em terminais onde o leitor não é compatível (ex.: UROVO i9100), o
     * WindowScanner.initialize lança ScannerException {1002} nessa coroutine — fora do
     * runCatching do GertecScannerManager — e a exceção não-capturada derrubava o app inteiro.
     *
     * Este handler global engole SOMENTE erros vindos do SDK de scanner (loga e segue), e
     * delega qualquer outra exceção ao handler anterior (comportamento normal de crash).
     */
    private fun installScannerCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isScannerSdkError(throwable)) {
                Log.e("MupaApplication", "Falha do scanner ignorada (não derruba o app): ${throwable.message}", throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun isScannerSdkError(t: Throwable?): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur.javaClass.simpleName == "ScannerException") return true
            if (cur.stackTrace.any { frame ->
                    val c = frame.className
                    c.contains("scansdk", ignoreCase = true) ||
                        c.contains("WindowScanner", ignoreCase = true) ||
                        c.contains("gertec.retailnexus", ignoreCase = true)
                }
            ) return true
            cur = cur.cause
            depth++
        }
        return false
    }
}
