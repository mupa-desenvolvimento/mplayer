package com.mupa.player.enterprise.demo

data class DemoProduct(
    val ean: String,
    val nome: String,
    val imagemUrl: String?,
    val preco: Double,
    val precoAntigo: Double?,
    val parcelamento: String?,
    val marca: String?,
    val categoria: String?,
)

data class DemoColors(
    val dominante: String,
    val escuro: String,
    val claro: String,
    val vibrante: String,
    val texto: String,
)

