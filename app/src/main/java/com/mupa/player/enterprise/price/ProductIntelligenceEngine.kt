package com.mupa.player.enterprise.price

/**
 * Ponto único de "inteligência de apresentação" sobre um [PriceProduct]: qual selo/explicação/
 * fala de TTS destacar ([OfferIntelligence]), qual frase amigável complementar mostrar
 * ([SmartDescription]) e qual slot de preço é o "melhor" entre vários (`pickBestSlot`).
 *
 * É uma fachada — a lógica em si continua em [OfferIntelligence]/[SmartDescription] (já
 * testadas em produção). `pickBestSlot` centraliza a regra hoje duplicada em
 * `populatePriceSlots`/`styleSlotBox` (`PlayerActivity.kt`): o slot de menor valor é o
 * destaque principal.
 */
object ProductIntelligenceEngine {

    fun analyzeOffer(product: PriceProduct): OfferInsight = OfferIntelligence.analyze(product)

    fun describeProduct(description: String?): SmartDescription.Result = SmartDescription.parse(description)

    /** Slot de menor valor entre os informados, ou null se a lista estiver vazia. */
    fun pickBestSlot(slots: List<ProductPriceSlot>): ProductPriceSlot? = slots.minByOrNull { it.value }
}
