package com.mupa.player.enterprise.price

/**
 * Contrato comum para qualquer integração de cliente/varejista. Cada adapter mantém sua
 * própria lógica de rede/autenticação/parsing (isso é inerente a cada API, não duplicação a
 * eliminar) — o único compromisso é devolver o [PriceProduct] (Modelo Universal) já com
 * `priceSlots` totalmente populado, para que a renderização use sempre o mesmo caminho
 * (Layout Universal), independente de qual varejista respondeu.
 */
interface PriceClientAdapter {
    suspend fun query(
        ean: String,
        config: PriceConfig,
        isOnline: Boolean,
        filial: String,
    ): PriceProduct?
}
