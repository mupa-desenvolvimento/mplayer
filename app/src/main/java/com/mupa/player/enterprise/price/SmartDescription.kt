package com.mupa.player.enterprise.price

import java.util.Locale

/**
 * Extrai, por regex, informações que já estão inequivocamente presentes no texto livre de
 * `description` (peso/volume, tipo/classificação) e monta uma frase amigável complementar.
 * Nunca inventa marca/categoria: se nada for reconhecido, [Result.friendlyPhrase] é null e a
 * UI simplesmente não mostra nada extra.
 */
object SmartDescription {

    data class Result(
        val sizeText: String?,
        val packagingHint: String?,
        val friendlyPhrase: String?,
    )

    private val SIZE_REGEX = Regex("(\\d+[.,]?\\d*)\\s*(KG|G|ML|L|UN)\\b", RegexOption.IGNORE_CASE)
    private val TYPE_REGEX = Regex("\\bTP\\s?(\\d)\\b|\\bTIPO\\s?(\\d)\\b", RegexOption.IGNORE_CASE)

    private val UNIT_NORMALIZATION = mapOf(
        "KG" to "kg",
        "G" to "g",
        "ML" to "ml",
        "L" to "L",
        "UN" to "un",
    )

    fun parse(description: String?): Result {
        val text = description?.trim().orEmpty()
        if (text.isBlank()) return Result(null, null, null)

        val upper = text.uppercase(Locale("pt", "BR"))

        val sizeMatch = SIZE_REGEX.findAll(upper).lastOrNull()
        val sizeText = sizeMatch?.let {
            val value = it.groupValues[1].replace(',', '.')
            val unit = UNIT_NORMALIZATION[it.groupValues[2].uppercase(Locale("pt", "BR"))] ?: it.groupValues[2].lowercase(Locale("pt", "BR"))
            val displayValue = if (value.endsWith(".0")) value.dropLast(2) else value
            "$displayValue$unit"
        }

        val typeMatch = TYPE_REGEX.find(upper)
        val packagingHint = typeMatch?.let {
            val digit = it.groupValues[1].ifBlank { it.groupValues[2] }
            "Tipo $digit"
        }

        val friendlyPhrase = when {
            sizeText != null && packagingHint != null -> "Embalagem de $sizeText, $packagingHint"
            sizeText != null -> "Embalagem de $sizeText"
            packagingHint != null -> packagingHint
            else -> null
        }

        return Result(sizeText, packagingHint, friendlyPhrase)
    }
}
