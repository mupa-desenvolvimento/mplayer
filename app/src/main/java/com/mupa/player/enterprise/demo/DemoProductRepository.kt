package com.mupa.player.enterprise.demo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DemoProductRepository(private val context: Context) {
    @Volatile
    private var cached: List<DemoProduct>? = null

    fun getDemoProduct(ean: String): DemoProduct? {
        val normalized = ean.trim()
        if (normalized.isBlank()) return null
        val list = cached ?: load().also { cached = it }
        return list.firstOrNull { it.ean == normalized }
    }

    private fun load(): List<DemoProduct> {
        val json = context.assets.open("demo-products.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        val out = ArrayList<DemoProduct>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ean = o.optString("ean", "").trim()
            val nome = o.optString("nome", "").trim()
            if (ean.isBlank() || nome.isBlank()) continue
            out += DemoProduct(
                ean = ean,
                nome = nome,
                imagemUrl = normalizeUrl(o.optString("imagem", "").trim()).ifBlank { null },
                preco = o.optString("preco", "0").trim().toDoubleOrNull() ?: 0.0,
                precoAntigo = o.optString("preco_antigo", "").trim().toDoubleOrNull()
                    ?: o.optString("de_por", "").trim().toDoubleOrNull(),
                parcelamento = o.optString("parcelamento", "").trim().ifBlank { null },
                marca = o.optString("marca", "").trim().ifBlank { null },
                categoria = o.optString("categoria", "").trim().ifBlank { null },
            )
        }
        return out
    }

    private fun normalizeUrl(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("`") && s.endsWith("`") && s.length >= 2) {
            s = s.substring(1, s.length - 1)
        }
        return s.trim()
    }
}

