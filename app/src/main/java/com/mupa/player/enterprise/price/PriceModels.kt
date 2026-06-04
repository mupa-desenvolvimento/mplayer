package com.mupa.player.enterprise.price

data class PriceProduct(
    val id: String?,
    val ean: String,
    val description: String?,
    val price: Double?,
    val stock: Int?,
    val image: String?,
    val offer: PriceOffer?,
    val packs: List<PricePack>,
    val theme: PriceTheme?,
    val offline: Boolean,
)

data class PriceOffer(
    val enabled: Boolean,
    val title: String?,
    val description: String?,
    val secondUnit: Double?,
    val type: String?,
)

data class PricePack(
    val label: String,
    val price: Double,
    val unitPrice: Double,
)

data class PriceTheme(
    val signature: String?,
    val light: String?,
    val dark: String?,
)
