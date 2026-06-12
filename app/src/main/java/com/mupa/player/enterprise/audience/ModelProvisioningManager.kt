package com.mupa.player.enterprise.audience

import android.content.Context
import android.util.Log
import com.mupa.player.enterprise.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL

object ModelProvisioningManager {

    private const val TAG = "ModelProvisioning"

    private val REQUIRED_MODELS = listOf(
        "age_gender_model.tflite",
        "mobilefacenet.tflite"
    )

    private val VALID_LICENSE_TYPES = setOf("facial", "analytics", "enterprise")

    /**
     * Ensures TFLite models are present in context.filesDir/models/.
     * Returns true if all models are ready (already present or successfully downloaded).
     * Returns false if license is invalid or download failed — engine will start in fallback mode.
     */
    suspend fun ensureModelsProvisioned(
        context: Context,
        tipoDaLicenca: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val licenseType = tipoDaLicenca?.trim()?.lowercase(java.util.Locale.US)
        if (licenseType !in VALID_LICENSE_TYPES) {
            Log.d(TAG, "License '$tipoDaLicenca' does not require model provisioning.")
            return@withContext false
        }

        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        var allReady = true
        for (modelName in REQUIRED_MODELS) {
            val modelFile = File(modelsDir, modelName)
            if (modelFile.exists() && modelFile.length() > 0L) {
                Log.d(TAG, "Model already present: $modelName (${modelFile.length()} bytes)")
                continue
            }
            val downloaded = downloadModel(modelName, modelFile)
            if (!downloaded) {
                allReady = false
            }
        }
        allReady
    }

    private fun downloadModel(modelName: String, targetFile: File): Boolean {
        val url = "${BuildConfig.TFLITE_MODELS_BASE_URL}$modelName"
        Log.i(TAG, "Downloading model: $url")
        return try {
            val connection = URL(url).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android; Zebra)")
            connection.connect()
            if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Server returned HTTP ${connection.responseCode} for $modelName")
                return false
            }
            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Model downloaded successfully: $modelName (${targetFile.length()} bytes)")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to download model $modelName: ${e.message}")
            targetFile.delete() // Remove partial file
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error downloading $modelName: ${e.message}")
            targetFile.delete()
            false
        }
    }
}
