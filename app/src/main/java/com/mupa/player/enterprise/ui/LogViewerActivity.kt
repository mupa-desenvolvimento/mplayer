package com.mupa.player.enterprise.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mupa.player.enterprise.R
import java.io.File

class LogViewerActivity : ComponentActivity() {
    private lateinit var pathText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        pathText = findViewById(R.id.pathText)
        logText = findViewById(R.id.logText)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { loadLog() }
        findViewById<Button>(R.id.copyButton).setOnClickListener { copyLogToClipboard() }
        findViewById<Button>(R.id.shareButton).setOnClickListener { shareLog() }

        loadLog()
    }

    private fun logFile(): File {
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val dir = File(baseDir, "mplayer_debug")
        return File(dir, "trae-debug-log-mplayer-renner-crash.ndjson")
    }

    private fun loadLog(): String {
        val f = logFile()
        pathText.text = "Log: ${f.absolutePath}"
        val content =
            runCatching {
                if (!f.exists()) return@runCatching "Arquivo de log ainda não existe."
                val bytes = f.readBytes()
                val max = 150_000
                if (bytes.size <= max) {
                    String(bytes)
                } else {
                    String(bytes.copyOfRange(bytes.size - max, bytes.size))
                }
            }.getOrElse { e ->
                "Falha ao ler log: ${e::class.java.simpleName}: ${e.message ?: ""}"
            }
        logText.text = content
        return content
    }

    private fun copyLogToClipboard() {
        val text = loadLog()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mplayer_log", text))
    }

    private fun shareLog() {
        val text = loadLog()
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "MPlayer Renner - crash log")
                putExtra(Intent.EXTRA_TEXT, text)
            }
        startActivity(Intent.createChooser(send, "Compartilhar log"))
    }
}

