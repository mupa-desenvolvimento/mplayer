package com.mupa.player.enterprise.price

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tipo de vantagem que o [OfferIntelligence] identificou como a mais relevante para o
 * consumidor. A ordem dos valores NÃO importa para a lógica (a prioridade está em
 * [OfferIntelligence.analyze]), só para leitura do enum.
 *
 * "Mais vendido" foi deliberadamente omitido: não há dado de vendas em [PriceProduct].
 * PIX e parcelamento também não têm campo de origem em [PriceProduct]/[PriceOffer] hoje.
 */
enum class AdvantageType {
    LEVE_PAGUE,
    COMPRE_GANHE,
    SEGUNDA_UNIDADE,
    CLUBE,
    ATACADO,
    CARTAO,
    DESCONTO_PERCENTUAL,
    ECONOMIA_ABSOLUTA,
    OFERTA_SIMPLES,
    SEM_VANTAGEM,
}

/**
 * Resultado da análise de [OfferIntelligence.analyze]: a única vantagem escolhida, pronta
 * para virar selo, frase de explicação e fala de TTS — sempre a mesma fonte de verdade para
 * os três, para nunca divergirem entre si.
 */
data class OfferInsight(
    val type: AdvantageType,
    val badgeEmoji: String,
    val badgeText: String,
    val shortExplanation: String?,
    val ttsPhrase: String,
)

/**
 * Motor de decisão único de "qual é a melhor vantagem para o consumidor". Determinístico,
 * síncrono, sem I/O — só interpreta campos que já existem em [PriceProduct]/[PriceOffer].
 * Não altera nem lê nada fora desses dois tipos; é puramente camada de apresentação.
 */
object OfferIntelligence {

    private const val MIN_PERCENT_FOR_BADGE = 0.10f

    /** Mesma regex usada por [splitOfferBadgesRegex]; fonte única de verdade para "LEVE X PAGUE Y". */
    val LEVE_PAGUE_REGEX = Regex("LEVE\\s+(\\d+)\\s+PAGUE\\s+(\\d+)")

    fun analyze(product: PriceProduct): OfferInsight {
        val offer = product.offer
        val basePrice = resolveBasePrice(product)

        if (offer?.enabled == true) {
            val title = offer.title?.trim().orEmpty()
            val description = offer.description?.trim().orEmpty()
            val combined = "$title $description".uppercase(Locale("pt", "BR"))

            val leveMatch = LEVE_PAGUE_REGEX.find(title.uppercase(Locale("pt", "BR")))
            if (leveMatch != null) {
                val leve = leveMatch.groupValues[1]
                val pague = leveMatch.groupValues[2]
                return OfferInsight(
                    type = AdvantageType.LEVE_PAGUE,
                    badgeEmoji = "🔥",
                    badgeText = "Leve $leve Pague $pague",
                    shortExplanation = "Leve $leve e pague apenas $pague.",
                    ttsPhrase = "Leve $leve, pague $pague.",
                )
            }

            if (combined.contains("GANHE") || combined.contains("BRINDE")) {
                return OfferInsight(
                    type = AdvantageType.COMPRE_GANHE,
                    badgeEmoji = "🎁",
                    badgeText = "Compre e Ganhe",
                    shortExplanation = description.ifBlank { "Compre e ganhe um brinde." },
                    ttsPhrase = "Compre e ganhe um brinde especial.",
                )
            }

            val second = offer.secondUnit
            if (second != null && second > 0.0) {
                return OfferInsight(
                    type = AdvantageType.SEGUNDA_UNIDADE,
                    badgeEmoji = "🏷",
                    badgeText = "2ª Unidade com Desconto",
                    shortExplanation = "A segunda unidade sai com desconto.",
                    ttsPhrase = "${title.ifBlank { "Oferta" }}. Valor total das duas unidades: ${buildSpokenPrice(second)}.",
                )
            }
        }

        val clube = firstValid(product.priceClub, product.clubPrice, below = basePrice)
        if (clube != null) {
            return OfferInsight(
                type = AdvantageType.CLUBE,
                badgeEmoji = "👑",
                badgeText = "Preço Clube",
                shortExplanation = "Preço exclusivo para clientes do clube.",
                ttsPhrase = "Preço exclusivo para clientes do clube, ${buildSpokenPrice(clube)}.",
            )
        }

        val atacado = firstValid(product.priceWholesale, below = basePrice)
        if (atacado != null) {
            return OfferInsight(
                type = AdvantageType.ATACADO,
                badgeEmoji = "📦",
                badgeText = "Menor no Atacado",
                shortExplanation = "Compre em maior quantidade e pague menos.",
                ttsPhrase = "Preço especial para compra em quantidade, ${buildSpokenPrice(atacado)}.",
            )
        }

        val cartao = firstValid(product.cardPrice, below = basePrice)
        if (cartao != null) {
            return OfferInsight(
                type = AdvantageType.CARTAO,
                badgeEmoji = "💳",
                badgeText = "Preço no Cartão",
                shortExplanation = "Preço exclusivo pagando no cartão.",
                ttsPhrase = "Preço exclusivo pagando no cartão, ${buildSpokenPrice(cartao)}.",
            )
        }

        val fromPrice = product.priceFrom ?: product.originalPrice
        if (fromPrice != null && basePrice != null && fromPrice > basePrice) {
            val percent = ((fromPrice - basePrice) / fromPrice)
            val savings = fromPrice - basePrice
            return if (percent >= MIN_PERCENT_FOR_BADGE) {
                val percentInt = (percent * 100).roundToInt()
                OfferInsight(
                    type = AdvantageType.DESCONTO_PERCENTUAL,
                    badgeEmoji = "💸",
                    badgeText = "Economia de $percentInt%",
                    shortExplanation = "Você economiza $percentInt% nesta compra.",
                    ttsPhrase = "De ${buildSpokenPrice(fromPrice)} por ${buildSpokenPrice(basePrice)}.",
                )
            } else {
                OfferInsight(
                    type = AdvantageType.ECONOMIA_ABSOLUTA,
                    badgeEmoji = "💰",
                    badgeText = "Economize ${formatCurrency(savings)}",
                    shortExplanation = "Você economiza ${formatCurrency(savings)}.",
                    ttsPhrase = "De ${buildSpokenPrice(fromPrice)} por ${buildSpokenPrice(basePrice)}. " +
                        "Você economiza ${buildSpokenPrice(savings)}.",
                )
            }
        }

        if (offer?.enabled == true && basePrice != null) {
            val description = offer.description?.trim().orEmpty()
            return OfferInsight(
                type = AdvantageType.OFERTA_SIMPLES,
                badgeEmoji = "🏷",
                badgeText = "Oferta Especial",
                shortExplanation = description.ifBlank { "Aproveite esta oferta." },
                ttsPhrase = "Produto em oferta. ${buildSpokenPrice(basePrice)}.",
            )
        }

        return OfferInsight(
            type = AdvantageType.SEM_VANTAGEM,
            badgeEmoji = "",
            badgeText = "",
            shortExplanation = null,
            ttsPhrase = basePrice?.let { buildSpokenPrice(it) }.orEmpty(),
        )
    }

    /** Menor preço "de venda" válido entre normal e promocional — mesma base usada para comparar as demais vantagens. */
    private fun resolveBasePrice(product: PriceProduct): Double? {
        val price = product.price?.takeIf { it > 0.0 }
        val promo = product.pricePromotional?.takeIf { it > 0.0 }
        return when {
            price != null && promo != null -> minOf(price, promo)
            promo != null -> promo
            else -> price
        }
    }

    private fun firstValid(vararg candidates: Double?, below: Double?): Double? {
        for (c in candidates) {
            if (c != null && c > 0.0 && (below == null || c < below)) return c
        }
        return null
    }

    private fun formatCurrency(value: Double): String {
        return String.format(Locale("pt", "BR"), "R$ %.2f", value)
    }

    fun buildSpokenPrice(value: Double): String {
        val cents = (value * 100.0).roundToInt().coerceAtLeast(0)
        val reais = cents / 100
        val cent = cents % 100
        val reaisStr = if (reais == 1) "real" else "reais"
        return if (cent == 0) {
            "$reais $reaisStr"
        } else {
            "$reais $reaisStr e $cent centavos"
        }
    }
}
