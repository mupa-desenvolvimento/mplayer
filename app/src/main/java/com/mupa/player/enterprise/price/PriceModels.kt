package com.mupa.player.enterprise.price

data class PriceSlot(
    val field: String,           // ex: "price", "price_promotional", "price_club", "price_wholesale", "price_weighable"
    val label: String,           // ex: "Preço Clube", "Oferta"
    val showFromPrice: Boolean   // se true, deve buscar e renderizar o preço 'price_from' (ou originalPrice) riscado para comparação
)

data class ProductPriceSlot(
    val label: String,
    val value: Double,
    val field: String,
    val isPromo: Boolean,
    val isClub: Boolean
)

data class LayoutConfig(
    val style: String,           // "default" (XML), "minimalist", "modern"
    val xmlLayoutType: String,   // "split", "split_inverted", "centered", "backdrop", "multi_price", "vertical_image_bottom", "vertical_image_top"
    val colorMode: String,       // "auto", "solid"
    val solidColor: String,      // "#06b6d4"
    val priceSlots: List<PriceSlot>
)

data class PriceProduct(
    val id: String?,
    val ean: String,
    val description: String?,
    val price: Double?,
    val originalPrice: Double?,
    val clubPrice: Double?,
    val pricePromotional: Double? = null,
    val priceClub: Double? = null,
    val priceWholesale: Double? = null,
    val priceWeighable: Double? = null,
    val priceFrom: Double? = null,
    val stock: Int?,
    val image: String?,
    val offer: PriceOffer?,
    val packs: List<PricePack>,
    val theme: PriceTheme?,
    val offline: Boolean,
    val priceSlots: List<ProductPriceSlot>? = null,
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

