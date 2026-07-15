package com.mupa.player.enterprise.audience

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the face-api.js model files required by [AudienceAnalyticsWebViewEngine]
 * from the jsDelivr CDN on first run, then caches them in [modelsDir].
 *
 * Layout expected by the WebView engine:
 *   modelsDir/libs/tf.min.js
 *   modelsDir/libs/face-api.min.js
 *   modelsDir/tiny_face_detector_model-weights_manifest.json
 *   modelsDir/tiny_face_detector_model-shard1
 *   modelsDir/age_gender_model-weights_manifest.json
 *   modelsDir/age_gender_model-shard1
 *   modelsDir/face_landmark_68_model-weights_manifest.json
 *   modelsDir/face_landmark_68_model-shard1
 *   modelsDir/face_recognition_model-weights_manifest.json
 *   modelsDir/face_recognition_model-shard1
 *   modelsDir/face_expression_model-weights_manifest.json
 *   modelsDir/face_expression_model-shard1
 */
class AudienceModelDownloader(private val modelsDir: File) {

    private val libsDir = File(modelsDir, "libs")

    // ─────────────────────────────────────────────────────────────────────────
    // Public
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true only when all required files exist with non-zero size. */
    fun isReady(): Boolean = REQUIRED_FILES.all { path ->
        File(modelsDir, path).let { it.exists() && it.length() > 0L }
    }

    /**
     * Ensures all model files are present, downloading missing ones from CDN.
     * Already-present files are skipped.
     * [onProgress] receives a human-readable status string for each file.
     *
     * Returns true when all files are ready, false on any network failure.
     */
    suspend fun ensureModels(
        onProgress: ((String) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        libsDir.mkdirs()

        // JS libraries
        for ((name, url) in JS_LIBS) {
            val dest = File(libsDir, name)
            if (dest.exists() && dest.length() > 0L) continue
            onProgress?.invoke("Baixando $name…")
            Log.i(TAG, "download_start file=$name")
            val ok = downloadTo(url, dest)
            if (!ok) {
                Log.w(TAG, "download_failed file=$name")
                return@withContext false
            }
            Log.i(TAG, "download_ok file=$name size=${dest.length()}")
        }

        // Model weight files
        for (name in MODEL_FILES) {
            val dest = File(modelsDir, name)
            if (dest.exists() && dest.length() > 0L) continue
            onProgress?.invoke("Baixando modelo: ${name.substringBefore('-')}…")
            val url = "$WEIGHTS_BASE$name"
            Log.i(TAG, "download_start file=$name")
            val ok = downloadTo(url, dest)
            if (!ok) {
                Log.w(TAG, "download_failed file=$name")
                return@withContext false
            }
            Log.i(TAG, "download_ok file=$name size=${dest.length()}")
        }

        isReady()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun downloadTo(urlStr: String, dest: File): Boolean {
        val tmp = File(dest.parent, "${dest.name}.tmp")
        return runCatching {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "MupaPlayer/1.0 Android")
            conn.connect()

            if (conn.responseCode != 200) {
                Log.w(TAG, "http_error url=$urlStr code=${conn.responseCode}")
                conn.disconnect()
                return false
            }

            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            }
            conn.disconnect()
            tmp.renameTo(dest)
            true
        }.onFailure { e ->
            Log.w(TAG, "download_exception url=$urlStr: ${e.message}")
            runCatching { tmp.delete() }
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "AudienceModels"

        // JS libs from jsDelivr (npm dist — correct path)
        private const val TF_VERSION = "4.17.0"
        private const val FACEAPI_VERSION = "0.22.2"

        // Model weights from the official face-api.js GitHub repo weights folder.
        // jsDelivr npm does NOT include the weights directory — use GitHub raw CDN instead.
        private const val WEIGHTS_BASE =
            "https://raw.githubusercontent.com/justadudewhohacks/face-api.js/master/weights/"

        private val JS_LIBS = listOf(
            "tf.min.js" to
                "https://cdn.jsdelivr.net/npm/@tensorflow/tfjs@$TF_VERSION/dist/tf.min.js",
            "face-api.min.js" to
                "https://cdn.jsdelivr.net/npm/face-api.js@$FACEAPI_VERSION/dist/face-api.min.js",
        )

        private val MODEL_FILES = listOf(
            "tiny_face_detector_model-weights_manifest.json",
            "tiny_face_detector_model-shard1",
            "age_gender_model-weights_manifest.json",
            "age_gender_model-shard1",
            "face_landmark_68_model-weights_manifest.json",
            "face_landmark_68_model-shard1",
            "face_recognition_model-weights_manifest.json",
            "face_recognition_model-shard1",
            "face_recognition_model-shard2",
            "face_expression_model-weights_manifest.json",
            "face_expression_model-shard1",
        )

        // Paths checked by isReady() — relative to modelsDir
        private val REQUIRED_FILES =
            JS_LIBS.map { "libs/${it.first}" } + MODEL_FILES
    }
}
