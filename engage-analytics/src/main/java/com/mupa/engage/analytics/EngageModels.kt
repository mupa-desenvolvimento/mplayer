package com.mupa.engage.analytics

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EngageConfig(
    val engageEnabled: Boolean,
    val engageVolume: Int,
    val engageTimeoutSeconds: Int,
    val engageIdleSeconds: Int,
    val engageQuestionsUrl: String?,
    val engageAnalyticsUrl: String?,
    val engageAnalyticsEnabled: Boolean,
)

data class EngageQuestion(
    val id: Long,
    val categoria: String?,
    val publico: String?,
    val pergunta: String,
    val opcao1: String,
    val opcao2: String,
)

data class EngageAnalyticsEvent(
    val date: String,
    val time: String,
    val faixaEtaria: String?,
    val emocao: String?,
    val participou: Boolean,
    val perguntaId: Long?,
    val resposta: Int?,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("data", date)
            .put("hora", time)
            .put("faixaEtaria", faixaEtaria ?: JSONObject.NULL)
            .put("emocao", emocao ?: JSONObject.NULL)
            .put("participou", participou)
            .put("perguntaId", perguntaId ?: JSONObject.NULL)
            .put("resposta", resposta ?: JSONObject.NULL)
    }

    companion object {
        fun now(
            faixaEtaria: String?,
            emocao: String?,
            participou: Boolean,
            perguntaId: Long?,
            resposta: Int?,
            now: Long = System.currentTimeMillis(),
        ): EngageAnalyticsEvent {
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            val d = Date(now)
            return EngageAnalyticsEvent(
                date = dateFmt.format(d),
                time = timeFmt.format(d),
                faixaEtaria = faixaEtaria,
                emocao = emocao,
                participou = participou,
                perguntaId = perguntaId,
                resposta = resposta,
            )
        }
    }
}

object EngageConfigParser {
    fun parse(manifestJson: String): EngageConfig {
        return runCatching {
            val root = JSONObject(manifestJson)
            val manifestObj = root.optJSONObject("manifest") ?: root

            val enabled = manifestObj.optBoolean("engageEnabled", false)
            val volume = manifestObj.optInt("engageVolume", 80).coerceIn(0, 100)
            val timeout = manifestObj.optInt("engageTimeout", 30).coerceIn(5, 120)
            val idle = manifestObj.optInt("engageIdleSeconds", 3).coerceIn(1, 20)
            val questionsUrl = manifestObj.optString("engageQuestionsUrl", "").trim().ifBlank { null }
            val analyticsUrl = manifestObj.optString("engageAnalyticsUrl", "").trim().ifBlank { null }
            val analyticsEnabled = manifestObj.optBoolean("engageAnalyticsEnabled", true)

            EngageConfig(
                engageEnabled = enabled,
                engageVolume = volume,
                engageTimeoutSeconds = timeout,
                engageIdleSeconds = idle,
                engageQuestionsUrl = questionsUrl,
                engageAnalyticsUrl = analyticsUrl,
                engageAnalyticsEnabled = analyticsEnabled,
            )
        }.getOrDefault(
            EngageConfig(
                engageEnabled = false,
                engageVolume = 80,
                engageTimeoutSeconds = 30,
                engageIdleSeconds = 3,
                engageQuestionsUrl = null,
                engageAnalyticsUrl = null,
                engageAnalyticsEnabled = true,
            ),
        )
    }
}
