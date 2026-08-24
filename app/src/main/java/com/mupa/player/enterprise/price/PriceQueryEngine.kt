package com.mupa.player.enterprise.price

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.network.TlsCompat
import com.mupa.player.enterprise.storage.db.AppDatabase
import com.mupa.player.enterprise.storage.db.MissingProductImageEntity
import com.mupa.player.enterprise.storage.db.PriceCacheEntity
import com.mupa.player.enterprise.storage.db.PriceQueryEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class PriceQueryEngine(
    private val context: Context,
    private val deviceId: String,
) {
    private val db = AppDatabase.get(context)

    // Timeouts agressivos: a prioridade nº1 do terminal é devolver o preço o mais rápido
    // possível. Se a rede estiver ruim, é melhor falhar rápido (e cair pro cache/offline) do
    // que deixar o cliente esperando 10s+ no padrão do OkHttp.
    private val http = TlsCompat.apply(OkHttpClient.Builder())
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    // Client com callTimeout maior para integrações que fazem 2 requests sequenciais (auth + price)
    private val httpMultiStep = TlsCompat.apply(OkHttpClient.Builder())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()
    private val sessionState = JSONObject()

    // Escopo separado para gravações de analytics (insertEvent) que não devem atrasar o
    // retorno do preço para a UI - são "fire and forget".
    private val analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // Cache de imagem de produto: válido por 60 min. Passado isso, a imagem local ainda é
        // exibida na hora (rápido), mas um novo download roda em background pra atualizar.
        private const val IMAGE_CACHE_TTL_MS = 60L * 60L * 1000L
    }

    // Pré-autentica em background para que o token esteja em cache antes do primeiro scan
    fun warmUpKompraoToken() {
        analyticsScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val cached = sessionState.optString("komprao_access_token", "")
                val cachedAt = sessionState.optLong("komprao_access_token_at", 0L)
                if (cached.isNotBlank() && (now - cachedAt) < 50 * 60 * 1000L) return@launch
                val authBody = """{"username":"MUPA.APP","password":"vRTz834/"}"""
                val authReq = Request.Builder()
                    .url("https://apionway.superkoch.com.br/on-way/auth/login")
                    .header("Content-Type", "application/json")
                    .post(authBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val authJson = httpMultiStep.newCall(authReq).execute().use { r ->
                    JSONObject(r.body?.string().orEmpty())
                }
                val token = authJson.optJSONObject("data")?.optString("token", "").orEmpty()
                if (token.isNotBlank()) {
                    sessionState.put("komprao_access_token", token)
                    sessionState.put("komprao_access_token_at", now)
                    Log.i("MPlayerPrice", "komprao_token_warmup_ok")
                }
            }.onFailure {
                Log.w("MPlayerPrice", "komprao_token_warmup_failed err=${it.message}")
            }
        }
    }

    @Volatile private var cachedFilial: String? = null

    private suspend fun resolveFilial(): String {
        cachedFilial?.let { return it }
        val resolved = runCatching { DeviceCacheManager(context).load()?.filial }.getOrNull().orEmpty()
        cachedFilial = resolved
        return resolved
    }

    @Volatile private var cachedCompanyId: String? = null

    private suspend fun resolveCompanyId(): String? {
        cachedCompanyId?.let { return it }
        val resolved = runCatching { DeviceCacheManager(context).load()?.company }.getOrNull()?.trim()?.ifBlank { null }
        cachedCompanyId = resolved
        return resolved
    }

    private fun insertEvent(
        ean: String,
        filial: String,
        responseTimeMs: Long,
        fromCache: Boolean,
        success: Boolean,
        descricao: String? = null,
    ) {
        analyticsScope.launch {
            runCatching {
                db.priceQueryEventDao().insert(
                    PriceQueryEventEntity(
                        id = UUID.randomUUID().toString(),
                        deviceId = deviceId,
                        filial = filial,
                        ean = ean,
                        descricao = descricao,
                        createdAtEpochMs = System.currentTimeMillis(),
                        responseTimeMs = responseTimeMs,
                        fromCache = fromCache,
                        success = success,
                        uploadedAtEpochMs = null,
                    )
                )
            }
        }
    }

    private data class ProductImageMeta(
        val imageUrl: String?,
        val signature: String?,
        val legibility: String?,
        val light: String?,
        val dark: String?,
    )

    suspend fun query(
        ean: String,
        config: PriceConfig,
        isOnline: Boolean,
    ): PriceProduct? = withContext(Dispatchers.IO) {
        val normalizedEan = ean.trim()
        if (normalizedEan.isBlank()) return@withContext null

        val now = System.currentTimeMillis()
        val filial = resolveFilial()

        val cache = db.priceCacheDao().getByEan(normalizedEan)
        val fromCache =
            cache != null && (now - cache.updatedAtEpochMs) <= config.cacheMinutes * 60 * 1000L

        if (fromCache) {
            val product = normalizeCachedProduct(parseProductFromCache(normalizedEan, cache))
            insertEvent(
                ean = normalizedEan,
                filial = filial,
                responseTimeMs = 0L,
                fromCache = true,
                success = product != null,
                descricao = product?.description,
            )
            return@withContext product
        }

        if (!isOnline) {
            val product = normalizeCachedProduct(parseProductFromCache(normalizedEan, cache))?.copy(offline = true)
            insertEvent(
                ean = normalizedEan,
                filial = filial,
                responseTimeMs = 0L,
                fromCache = false,
                success = product != null,
                descricao = product?.description,
            )
            return@withContext product
        }

        if (config.integration == "integra-assai") {
            return@withContext runCatching { queryAssai(normalizedEan, config, isOnline, filial) }
                .onFailure {
                    Log.w(
                        "MPlayerPrice",
                        "query_failed integration=integra-assai ean=$normalizedEan err=${it.javaClass.simpleName}:${it.message}",
                    )
                }
                .getOrNull()
        }

        if (config.integration == "integra-americana") {
            return@withContext runCatching { queryAmericanas(normalizedEan, config, isOnline, filial) }
                .onFailure {
                    Log.w(
                        "MPlayerPrice",
                        "query_failed integration=integra-americana ean=$normalizedEan err=${it.javaClass.simpleName}:${it.message}",
                    )
                }
                .getOrNull()
        }

        if (config.integration == "integra-zaffari") {
            return@withContext runCatching { queryZaffari(normalizedEan, config, isOnline, filial) }
                .onFailure {
                    Log.w(
                        "MPlayerPrice",
                        "query_failed integration=integra-zaffari ean=$normalizedEan err=${it.javaClass.simpleName}:${it.message}",
                    )
                }
                .getOrNull()
        }

        if (config.integration == "integra-komprao") {
            return@withContext runCatching { queryKomprao(normalizedEan, config, isOnline, filial) }
                .onFailure {
                    Log.w(
                        "MPlayerPrice",
                        "query_failed integration=integra-komprao ean=$normalizedEan err=${it.javaClass.simpleName}:${it.message}",
                    )
                }
                .getOrNull()
        }

        if (config.integration == "integra-tcserver") {
            return@withContext runCatching { queryTcServer(normalizedEan, config, filial) }
                .onFailure {
                    Log.w(
                        "MPlayerPrice",
                        "query_failed integration=integra-tcserver ean=$normalizedEan err=${it.javaClass.simpleName}:${it.message}",
                    )
                }
                .getOrNull()
        }

        val startedAt = System.currentTimeMillis()
        val state = JSONObject()
            .put("ean", normalizedEan)
            .put("device", deviceId)
            .put("filial", filial)
            .put("loja", filial)
            .put("store_id", filial)
            .put("integration", config.integration)

        var product: PriceProduct? = null
        try {
            for (step in config.steps) {
                when (step.type) {
                    "authenticate" -> {
                        val cachedToken = sessionState.optString("access_token", "")
                        val cachedAt = sessionState.optLong("access_token_at", 0L)
                        val currentTime = System.currentTimeMillis()
                        if (cachedToken.isNotBlank() && (currentTime - cachedAt) < 50 * 60 * 1000L) {
                            state.put("access_token", cachedToken)
                            sessionState.keys().forEach { k ->
                                if (k != "access_token_at") {
                                    state.put(k, sessionState.opt(k))
                                }
                            }
                        } else {
                            val resp = executeStep(step, state)
                            applyMapping(resp, step.mapping, state)
                            step.mapping.keys.forEach { targetKey ->
                                val v = state.opt(targetKey)
                                if (v != null) {
                                    sessionState.put(targetKey, v)
                                    if (targetKey == "access_token") {
                                        sessionState.put("access_token_at", currentTime)
                                    }
                                }
                            }
                        }
                    }
                    "lookup_internal_code" -> {
                        val resp = executeStep(step, state)
                        applyMapping(resp, step.mapping, state)
                    }
                    "lookup_price" -> {
                        val resp = executeStep(step, state)
                        applyMapping(resp, step.mapping, state)
                        val sku = resp.optJSONObject("data")?.optString("sku")
                            ?: resp.optString("sku")
                            ?: resp.optJSONObject("data")?.optString("id_produto")
                            ?: resp.optString("id_produto")
                        if (!sku.isNullOrBlank()) {
                            state.put("sku", sku)
                            if (state.optString("id", "").isBlank()) {
                                state.put("id", sku)
                            }
                        }
                    }
                    "lookup_image" -> {
                        Unit
                    }
                    else -> {
                        val resp = executeStep(step, state)
                        applyMapping(resp, step.mapping, state)
                    }
                }
            }

            // Alguns mappings (ex: Komprão) sobrescrevem "ean" com o valor bruto da resposta da API
            // (que pode ser um array). Sempre usar o EAN normalizado da consulta original.
            state.put("ean", normalizedEan)

            product =
                buildFinalProduct(state)
                    ?.let { attachLocalImageIfExists(it) }
            if (product != null) {
                db.priceCacheDao().upsert(
                    PriceCacheEntity(
                        ean = normalizedEan,
                        productJson = productToJson(product).toString(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        } catch (t: Throwable) {
            Log.w(
                "MPlayerPrice",
                "query_failed integration=${config.integration} ean=$normalizedEan err=${t.javaClass.simpleName}:${t.message}",
            )
        } finally {
            val took = System.currentTimeMillis() - startedAt
            insertEvent(
                ean = normalizedEan,
                filial = filial,
                responseTimeMs = took,
                fromCache = false,
                success = product != null,
                descricao = product?.description,
            )
        }

        product
    }

    private suspend fun queryAssai(ean: String, config: PriceConfig, isOnline: Boolean, filial: String): PriceProduct? {
        val now = System.currentTimeMillis()
        val cache = db.priceCacheDao().getByEan(ean)
        val fromCache =
            cache != null && (now - cache.updatedAtEpochMs) <= config.cacheMinutes * 60 * 1000L

        if (fromCache) {
            val product = normalizeCachedProduct(parseProductFromCache(ean, cache))
            insertEvent(
                ean = ean,
                filial = filial,
                responseTimeMs = 0L,
                fromCache = true,
                success = product != null,
                descricao = product?.description,
            )
            return product
        }

        if (!isOnline) {
            val product = normalizeCachedProduct(parseProductFromCache(ean, cache))?.copy(offline = true)
            insertEvent(
                ean = ean,
                filial = filial,
                responseTimeMs = 0L,
                fromCache = false,
                success = product != null,
                descricao = product?.description,
            )
            return product
        }

        val startedAt = System.currentTimeMillis()
        var product: PriceProduct? = null
        try {
            Log.i("ASSAI_EAN", "[ASSAI_EAN] ean=$ean")

            val seqInfo = fetchAssaiSeqProduto(ean) ?: return null
            val seqProduto = seqInfo.first
            val descCompleta = seqInfo.second
            Log.i("ASSAI_SEQPRODUTO", "[ASSAI_SEQPRODUTO] ean=$ean seqProduto=$seqProduto")

            val storeId = filial.toIntOrNull() ?: 144
            val state =
                JSONObject()
                    .put("ean", ean)
                    .put("SEQPRODUTO", seqProduto)
                    .put("id_product", seqProduto)
                    .put("store_id", storeId)
                    .put("id_store", storeId)

            val step2 = config.steps.firstOrNull { it.type == "lookup_price" }
            val stockObj =
                runCatching {
                    if (step2 != null) {
                        executeStep(step2, state)
                    } else {
                        fetchAssaiStock(seqProduto = seqProduto, storeId = storeId)
                    }
                }.getOrElse {
                    Log.w(
                        "MPlayerPrice",
                        "assai_stock_failed ean=$ean seqProduto=$seqProduto err=${it.javaClass.simpleName}:${it.message}",
                    )
                    JSONObject()
                }

            val stockPriceArr = stockObj.optJSONArray("stock_price")
                ?: stockObj.optJSONArray("stock_prices")
                ?: JSONArray()

            var mainPrice: Double? = null
            var stock: Int? = null
            val packs = ArrayList<PricePack>()
            for (i in 0 until stockPriceArr.length()) {
                val o = stockPriceArr.optJSONObject(i) ?: continue
                val unitPack = o.optInt("unit_pack", 0)
                val promPrice = o.optDouble("price_prom_pack", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                val pricePack = promPrice ?: o.optDouble("price_pack", Double.NaN).takeIf { !it.isNaN() } ?: 0.0
                if (pricePack <= 0.0) continue
                if (unitPack == 1) {
                    mainPrice = pricePack
                    stock = o.optInt("stock_avaliable", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                        ?: o.optInt("stock_available", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                } else if (unitPack > 1) {
                    val unit = pricePack / unitPack.toDouble()
                    packs += PricePack(
                        label = "Leve $unitPack unidades",
                        price = pricePack,
                        unitPrice = unit,
                    )
                }
            }

            if (mainPrice == null) {
                var bestUnit: Int? = null
                var bestPackPrice: Double? = null
                for (i in 0 until stockPriceArr.length()) {
                    val o = stockPriceArr.optJSONObject(i) ?: continue
                    val unitPack = o.optInt("unit_pack", 0)
                    if (unitPack <= 0) continue
                    val pricePack = o.optDouble("price_pack", Double.NaN).takeIf { !it.isNaN() } ?: 0.0
                    if (pricePack <= 0.0) continue
                    if (bestUnit == null || unitPack < bestUnit) {
                        bestUnit = unitPack
                        bestPackPrice = pricePack
                    }
                }
                if (bestUnit != null && bestPackPrice != null) {
                    mainPrice = if (bestUnit > 1) bestPackPrice / bestUnit.toDouble() else bestPackPrice
                }
            }

            Log.i("ASSAI_PRICE", "[ASSAI_PRICE] ean=$ean seqProduto=$seqProduto mainPrice=${mainPrice ?: "null"}")
            Log.i("ASSAI_PACKS", "[ASSAI_PACKS] ean=$ean seqProduto=$seqProduto packs=${packs.size}")

            val localImagePath = localProductImagePathIfExists(ean)

            val productId = seqProduto.ifBlank { null }
            product =
                PriceProduct(
                    id = productId,
                    ean = ean,
                    description = descCompleta.ifBlank { null },
                    price = mainPrice,
                    originalPrice = null,
                    clubPrice = null,
                    stock = stock,
                    image = localImagePath,
                    offer = null,
                    packs = packs,
                    theme = null,
                    offline = false,
                )

            db.priceCacheDao().upsert(
                PriceCacheEntity(
                    ean = ean,
                    productJson = productToJson(product).toString(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        } catch (t: Throwable) {
            Log.w(
                "MPlayerPrice",
                "query_assai_failed ean=$ean err=${t.javaClass.simpleName}:${t.message}",
            )
        } finally {
            val took = System.currentTimeMillis() - startedAt
            insertEvent(
                ean = ean,
                filial = filial,
                responseTimeMs = took,
                fromCache = false,
                success = product != null,
                descricao = product?.description,
            )
        }

        return product
    }

    // ────────────���───────────────────────────���────────────────────────────────
    // Integração Zaffari
    // API: GET https://zaffariexpress.com.br/api/v1/consultapreco/precos?loja={loja}&ean={ean}
    // Auth: Bearer JWT (1h) via POST https://zaffariexpress.com.br/api/login/login
    // Resposta: { success, data: { preco_base, preco_clube, preco_apartirde,
    //   preco_prop_sellprice, preco_prop_clube, codigo_etiqueta, descricao_produto,
    //   embalagem_venda, qtd_apartirde, link_imagem, ... } }
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun queryZaffari(ean: String, config: PriceConfig, isOnline: Boolean, filial: String): PriceProduct? {
        val now = System.currentTimeMillis()
        val cache = db.priceCacheDao().getByEan(ean)
        val fromCache = cache != null && (now - cache.updatedAtEpochMs) <= config.cacheMinutes * 60 * 1000L

        if (fromCache) {
            val product = normalizeCachedProduct(parseProductFromCache(ean, cache))
            insertEvent(ean = ean, filial = filial, responseTimeMs = 0L, fromCache = true, success = product != null, descricao = product?.description)
            return product
        }

        if (!isOnline) {
            val product = normalizeCachedProduct(parseProductFromCache(ean, cache))?.copy(offline = true)
            insertEvent(ean = ean, filial = filial, responseTimeMs = 0L, fromCache = false, success = product != null, descricao = product?.description)
            return product
        }

        val startedAt = System.currentTimeMillis()
        var product: PriceProduct? = null
        try {
            val state = JSONObject()
                .put("ean", ean)
                .put("filial", filial)
                .put("loja", filial)
                .put("store_id", filial)

            // 1. Autenticação: step "authenticate" obrigatório para obter Bearer token
            val authStep = config.steps.firstOrNull { it.type == "authenticate" }
            var bearerToken = ""
            if (authStep != null) {
                val cachedToken = sessionState.optString("zaffari_access_token", "")
                val cachedAt = sessionState.optLong("zaffari_access_token_at", 0L)
                if (cachedToken.isNotBlank() && (now - cachedAt) < 55 * 60 * 1000L) {
                    bearerToken = cachedToken
                } else {
                    val resp = executeStep(authStep, state)
                    // A API Zaffari retorna o token no campo "token"
                    val token = resp.optString("token", "").ifBlank {
                        resp.optString("access_token", "").ifBlank {
                            applyMapping(resp, authStep.mapping, state)
                            state.optString("access_token", "")
                        }
                    }
                    if (token.isNotBlank()) {
                        bearerToken = token
                        sessionState.put("zaffari_access_token", token)
                        sessionState.put("zaffari_access_token_at", now)
                    }
                }
            }

            // 2. Consulta de preço
            val lookupStep = config.steps.firstOrNull { it.type == "lookup_price" }
            val priceResp = if (lookupStep != null) {
                state.put("access_token", bearerToken)
                executeStep(lookupStep, state)
            } else {
                fetchZaffariPrice(ean = ean, loja = filial, bearerToken = bearerToken)
            }

            // 3. Parse → PriceProduct
            product = parseZaffariResponse(ean = ean, json = priceResp)?.let { attachLocalImageIfExists(it) }

            if (product != null) {
                db.priceCacheDao().upsert(
                    PriceCacheEntity(
                        ean = ean,
                        productJson = productToJson(product).toString(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        } catch (t: Throwable) {
            Log.w("MPlayerPrice", "query_zaffari_failed ean=$ean err=${t.javaClass.simpleName}:${t.message}")
        } finally {
            val took = System.currentTimeMillis() - startedAt
            insertEvent(ean = ean, filial = filial, responseTimeMs = took, fromCache = false, success = product != null, descricao = product?.description)
        }

        return product
    }

    private fun fetchZaffariPrice(ean: String, loja: String, bearerToken: String): JSONObject {
        val resolvedLoja = loja.ifBlank { "47" }
        val url = "https://zaffariexpress.com.br/api/v1/consultapreco/precos?loja=$resolvedLoja&ean=$ean"
        val reqBuilder = Request.Builder().url(url)
        if (bearerToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $bearerToken")
        }
        http.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        }
    }

    private fun parseZaffariResponse(ean: String, json: JSONObject): PriceProduct? {
        if (!json.optBoolean("success", true)) return null
        val data = json.optJSONObject("data") ?: return null

        fun field(key: String): Double? =
            data.optString(key, "").trim().replace(",", ".").toDoubleOrNull()?.takeIf { it > 0 }

        val precoBase = field("preco_base") ?: return null
        val precoClube = field("preco_clube")?.takeIf { it < precoBase }
        val precoApartirde = field("preco_apartirde")?.takeIf { it < precoBase }
        val precoApartirdeClube = field("preco_apartirdeclube")?.takeIf { it < precoBase }
        val qtdApartirde = data.optString("qtd_apartirde", "").trim().toIntOrNull() ?: 0

        // preco_prop_* = preço por KG (produtos pesáveis)
        val precoPropBase = field("preco_prop_sellprice")
        val precoPropClube = field("preco_prop_clube")?.takeIf { pc -> precoClube != null || (precoPropBase != null && pc < precoPropBase) }
        val precoPropApartirde = field("preco_prop_apartirde")

        val description = data.optString("descricao_produto", "").ifBlank { null }
        val embalagVenda = data.optString("embalagem_venda", "UN").trim().uppercase().ifBlank { "UN" }
        val embalagProp = data.optString("embalagem_proporcional", "").trim().uppercase()
        val clientImageUrl = data.optString("link_imagem", "").ifBlank { null }
        val codigoProduto = data.optString("codigo_produto", "").ifBlank { null }

        // Pesável apenas quando a unidade de venda ou proporcional é KG
        val isPesavel = embalagVenda == "KG" || embalagProp == "KG"

        fun unitLabel(unit: String) = when (unit) {
            "KG" -> "KG"
            "UN", "" -> "UNIDADE"
            else -> unit
        }

        // Preço promocional (antigo "atacado"): preco_apartirde a partir de qtd unidades
        val precoPromo = precoApartirdeClube ?: precoApartirde
        val bulkPropPrice = precoPropApartirde

        // Descrição enriquecida: adiciona condição da promoção quando aplicável
        val promoCondition = when {
            precoPromo != null && qtdApartirde > 0 ->
                "A partir de $qtdApartirde ${unitLabel(embalagVenda).lowercase()} para o preço em promoção"
            precoPromo != null -> "Preço em promoção"
            else -> null
        }
        val enrichedDescription = listOfNotNull(description, promoCondition)
            .joinToString(" • ").ifBlank { null }

        // ── Monta priceSlots: base → EM PROMOÇÃO → CLUBE K (D3/D4) ──
        val slots = ArrayList<ProductPriceSlot>()

        if (isPesavel && precoPropBase != null) {
            slots += ProductPriceSlot(
                label = "PREÇO POR KG",
                value = precoPropBase,
                field = "price_weighable",
                isPromo = false,
                isClub = false,
            )
        } else {
            slots += ProductPriceSlot(
                label = "PREÇO/${unitLabel(embalagVenda)}",
                value = precoBase,
                field = "price",
                isPromo = false,
                isClub = false,
            )
        }

        // D1/D3: Promoção (antigo bulk) vem antes do clube, rotulado como "EM PROMOÇÃO"
        if (precoPromo != null) {
            slots += ProductPriceSlot(
                label = "EM PROMOÇÃO",
                value = precoPromo,
                field = "price_wholesale",
                isPromo = true,
                isClub = false,
            )
        } else if (isPesavel && bulkPropPrice != null) {
            slots += ProductPriceSlot(
                label = "PROMOÇÃO POR KG",
                value = bulkPropPrice,
                field = "price_wholesale",
                isPromo = true,
                isClub = false,
            )
        }

        // D3/D4: Clube K — exibido apenas quando existe preço clube menor que o base
        if (precoClube != null) {
            slots += ProductPriceSlot(
                label = "CLUBE K",
                value = precoClube,
                field = "price_club",
                isPromo = false,
                isClub = true,
            )
        }
        if (isPesavel && precoPropClube != null) {
            slots += ProductPriceSlot(
                label = "CLUBE K POR KG",
                value = precoPropClube,
                field = "price_club",
                isPromo = false,
                isClub = true,
            )
        }

        // Offer badge (mantido para compatibilidade com outros layouts)
        val offer: PriceOffer? = if (precoPromo != null && qtdApartirde > 0) {
            PriceOffer(
                enabled = true,
                title = "A PARTIR DE $qtdApartirde ${unitLabel(embalagVenda)}",
                description = null,
                secondUnit = precoPromo,
                type = "bulk",
            )
        } else null

        val localImagePath = localProductImagePathIfExists(ean)

        return PriceProduct(
            id = codigoProduto,
            ean = ean,
            description = enrichedDescription,
            price = if (isPesavel) precoPropBase ?: precoBase else precoBase,
            originalPrice = null,
            priceFrom = null,
            pricePromotional = null,
            clubPrice = precoClube,
            priceClub = precoClube,
            priceWholesale = precoPromo,
            priceWeighable = if (isPesavel) precoPropBase else null,
            cardPrice = null,
            stock = null,
            image = localImagePath,
            clientImageUrl = clientImageUrl,
            offer = offer,
            packs = emptyList(),
            theme = null,
            offline = false,
            priceSlots = slots.ifEmpty { null },
            xmlLayoutType = "multi_price",
        )
    }

    private suspend fun queryAmericanas(ean: String, config: PriceConfig, isOnline: Boolean, filial: String): PriceProduct? {
        val now = System.currentTimeMillis()
        val cache = db.priceCacheDao().getByEan(ean)
        val fromCache = cache != null && (now - cache.updatedAtEpochMs) <= config.cacheMinutes * 60 * 1000L

        if (fromCache) {
            val product = normalizeCachedProduct(parseProductFromCache(ean, cache))
            insertEvent(ean = ean, filial = filial, responseTimeMs = 0L, fromCache = true, success = product != null, descricao = product?.description)
            return product
        }

        if (!isOnline) {
            val product = normalizeCachedProduct(parseProductFromCache(ean, cache))?.copy(offline = true)
            insertEvent(ean = ean, filial = filial, responseTimeMs = 0L, fromCache = false, success = product != null, descricao = product?.description)
            return product
        }

        val startedAt = System.currentTimeMillis()
        var product: PriceProduct? = null
        try {
            val state = JSONObject()
                .put("ean", ean)
                .put("filial", filial)
                .put("store_id", filial)
                .put("storeId", filial)

            // 1. Autenticação (Bearer token via step "authenticate", se configurado)
            val authStep = config.steps.firstOrNull { it.type == "authenticate" }
            var bearerToken = ""
            if (authStep != null) {
                val cachedToken = sessionState.optString("access_token", "")
                val cachedAt = sessionState.optLong("access_token_at", 0L)
                if (cachedToken.isNotBlank() && (now - cachedAt) < 50 * 60 * 1000L) {
                    bearerToken = cachedToken
                    state.put("access_token", cachedToken)
                } else {
                    val resp = executeStep(authStep, state)
                    applyMapping(resp, authStep.mapping, state)
                    bearerToken = state.optString("access_token", "")
                    if (bearerToken.isNotBlank()) {
                        sessionState.put("access_token", bearerToken)
                        sessionState.put("access_token_at", now)
                    }
                }
            }

            // 2. Consulta de preço: usa step "lookup_price" se configurado, senão endpoint padrão Americana
            val lookupStep = config.steps.firstOrNull { it.type == "lookup_price" }
            val priceResp = if (lookupStep != null) {
                executeStep(lookupStep, state)
            } else {
                fetchAmericanasPrice(ean = ean, storeId = filial, bearerToken = bearerToken)
            }

            // 3. Parse da resposta → PriceProduct
            product = parseAmericanasResponse(ean = ean, json = priceResp)?.let { attachLocalImageIfExists(it) }

            if (product != null) {
                db.priceCacheDao().upsert(
                    PriceCacheEntity(
                        ean = ean,
                        productJson = productToJson(product).toString(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        } catch (t: Throwable) {
            Log.w("MPlayerPrice", "query_americana_failed ean=$ean err=${t.javaClass.simpleName}:${t.message}")
        } finally {
            val took = System.currentTimeMillis() - startedAt
            insertEvent(ean = ean, filial = filial, responseTimeMs = took, fromCache = false, success = product != null, descricao = product?.description)
        }

        return product
    }

    private fun fetchAmericanasPrice(ean: String, storeId: String, bearerToken: String): JSONObject {
        val url = "https://pricing-query.azr.internal.americanas.io/price?storeId=$storeId&ean=$ean"
        val reqBuilder = Request.Builder().url(url)
        if (bearerToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $bearerToken")
        }
        http.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        }
    }

    private fun parseAmericanasResponse(ean: String, json: JSONObject): PriceProduct? {
        val items = json.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val item = items.optJSONObject(0) ?: return null

        val productObj = item.optJSONObject("product")
        val description = productObj?.optString("description")?.ifBlank { null }
        val sapId = productObj?.optString("sapId")?.ifBlank { null }

        val regularPrice = item.optDouble("regularPrice", Double.NaN).takeIf { !it.isNaN() && it > 0 }

        val promoObj = item.optJSONObject("promotional")
        val promoPrice = promoObj?.optDouble("price", Double.NaN)?.takeIf { !it.isNaN() && it > 0 }

        val takeWinObj = item.optJSONObject("takeWin")
        var offer: PriceOffer? = null
        val packs = ArrayList<PricePack>()
        if (takeWinObj != null) {
            val qty = takeWinObj.optInt("quantity", 0)
            val totalWithDiscount = takeWinObj.optDouble("totalPriceWithDiscount", Double.NaN).takeIf { !it.isNaN() && it > 0 }
            val unitWithDiscount = takeWinObj.optDouble("unitPriceWithDiscount", Double.NaN).takeIf { !it.isNaN() && it > 0 }
            val discountValue = takeWinObj.optDouble("discountValue", Double.NaN).takeIf { !it.isNaN() && it > 0 }
            val type = takeWinObj.optString("type", "").ifBlank { null }

            if (qty > 0 && totalWithDiscount != null) {
                packs += PricePack(
                    label = "Leve $qty unidades",
                    price = totalWithDiscount,
                    unitPrice = unitWithDiscount ?: (totalWithDiscount / qty),
                )
                offer = PriceOffer(
                    enabled = true,
                    title = "COMPRE $qty",
                    description = discountValue?.let { "Economize R$ ${"%.2f".format(it).replace('.', ',')}" },
                    secondUnit = unitWithDiscount,
                    type = type,
                )
            }
        }

        // Se há preço promocional menor que o regular → layout De/Por
        val hasPromo = promoPrice != null && regularPrice != null && promoPrice < regularPrice
        val xmlLayoutType = if (hasPromo) "price_check_de_por" else null

        val localImagePath = localProductImagePathIfExists(ean)

        return PriceProduct(
            id = sapId,
            ean = ean,
            description = description,
            price = regularPrice,
            originalPrice = if (hasPromo) regularPrice else null,
            priceFrom = if (hasPromo) regularPrice else null,
            pricePromotional = if (hasPromo) promoPrice else null,
            clubPrice = null,
            priceClub = null,
            priceWholesale = null,
            priceWeighable = null,
            cardPrice = null,
            stock = null,
            image = localImagePath,
            clientImageUrl = null,
            offer = offer,
            packs = packs,
            theme = null,
            offline = false,
            xmlLayoutType = xmlLayoutType,
        )
    }

    private fun attachLocalImageIfExists(
        product: PriceProduct,
    ): PriceProduct {
        val sanitizedExisting = sanitizeLocalPathOrNull(product.image)
        if (sanitizedExisting != null) {
            return product.copy(image = sanitizedExisting)
        }
        val local = localProductImagePathIfExists(product.ean)
        return product.copy(image = local)
    }

    suspend fun preloadProductImageAndTheme(
        ean: String,
        config: PriceConfig,
        isOnline: Boolean,
    ): Pair<String?, PriceTheme?> = withContext(Dispatchers.IO) {
        val normalizedEan = ean.trim()
        if (normalizedEan.isBlank()) return@withContext null to null

        val existing = localProductImagePathIfExists(normalizedEan)
        // Cache 60 min: imagem fresca é servida direto; vencida, tenta rebaixar (cai no fluxo
        // abaixo). Offline, serve a imagem que houver (fresca ou vencida) — melhor que nada.
        if (existing != null && isProductImageFresh(normalizedEan)) return@withContext existing to null
        if (!isOnline) return@withContext existing to null

        val cachedProduct = db.priceCacheDao().getByEan(normalizedEan)?.let { cache ->
            runCatching { parseProductFromCache(normalizedEan, cache) }.getOrNull()
        }

        suspend fun saveToCacheAndReturn(path: String, t: PriceTheme?): Pair<String?, PriceTheme?> {
            val cache = db.priceCacheDao().getByEan(normalizedEan)
            if (cache != null) {
                runCatching {
                    val cachedProd = parseProductFromCache(normalizedEan, cache)
                    if (cachedProd != null) {
                        val updatedProd = cachedProd.copy(image = path, theme = t ?: cachedProd.theme)
                        db.priceCacheDao().upsert(
                            PriceCacheEntity(
                                ean = normalizedEan,
                                productJson = productToJson(updatedProd).toString(),
                                updatedAtEpochMs = cache.updatedAtEpochMs,
                            )
                        )
                    }
                }
            }
            return path to t
        }

        // 1. Prioritize client-provided remote image URL
        val clientImageUrl = cachedProduct?.clientImageUrl
        if (!clientImageUrl.isNullOrBlank() && clientImageUrl.startsWith("http")) {
            val clientLocal = runCatching { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = clientImageUrl) }.getOrNull()
            if (clientLocal != null) return@withContext saveToCacheAndReturn(clientLocal, null)
        }

        // 2. Alternative search for Komprão (OnWay SKU image API)
        val isKomprao = config.integration.contains("komprao", ignoreCase = true) || config.integration.contains("komprão", ignoreCase = true)
        if (isKomprao) {
            val sku = cachedProduct?.id ?: normalizedEan
            val token = obterTokenKomprao()
            if (!sku.isNullOrBlank() && token.isNotBlank()) {
                val kompraoUrl = "https://apionway.superkoch.com.br/on-way/product/image/$sku"
                val headers = mapOf("accept" to "image/jpeg", "Authorization" to "Bearer $token")
                val kompraoLocal = runCatching {
                    downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = kompraoUrl, headers = headers)
                }.getOrNull()
                Log.d("MPlayerPrice", "komprao_image sku=$sku ok=${kompraoLocal != null}")
                if (kompraoLocal != null) return@withContext saveToCacheAndReturn(kompraoLocal, null)
            } else {
                Log.w("MPlayerPrice", "komprao_image_skip sku=$sku hasToken=${token.isNotBlank()}")
            }
        }

        // 3. Fallback to normal VTEX and Mupa image flow
        val vtexUrl = fetchVtexImageUrlFromApiProdutos(ean = normalizedEan)
        val vtexLocal = vtexUrl?.let { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = it) }
        if (vtexLocal != null) return@withContext saveToCacheAndReturn(vtexLocal, null)

        // 2ª opção garantida: API de imagem da Mupa (srv-mupa). Tenta primeiro o step
        // "lookup_image" do config (se houver) e, se ele não trouxer imagem, cai SEMPRE no
        // srv-mupa hardcoded — assim o fallback da Mupa acontece independente do config.
        val step3 = config.steps.firstOrNull { it.type == "lookup_image" }
        var meta = fetchProductImageMeta(ean = normalizedEan, step = step3)
        var mupaLocal = meta?.imageUrl?.let { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = it) }
        if (mupaLocal == null && step3 != null) {
            val fallbackMeta = fetchProductImageMeta(ean = normalizedEan, step = null)
            val fallbackLocal = fallbackMeta?.imageUrl?.let { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = it) }
            if (fallbackLocal != null) {
                meta = fallbackMeta
                mupaLocal = fallbackLocal
            }
        }
        val theme =
            meta?.let {
                PriceTheme(
                    signature = it.signature,
                    light = it.light,
                    dark = it.dark,
                )
            }?.takeIf { it.signature != null || it.dark != null || it.light != null }
        if (mupaLocal != null) return@withContext saveToCacheAndReturn(mupaLocal, theme)

        // Todas as fontes falharam: registra o EAN para um técnico resolver manualmente depois.
        // Fire-and-forget — não afeta o retorno nem a velocidade da consulta de preço.
        reportMissingImage(normalizedEan)
        null to null
    }

    /** Registra localmente que [ean] está sem imagem em nenhuma fonte; sincronizado com o backend depois, em lote. */
    private suspend fun reportMissingImage(ean: String) {
        runCatching {
            db.missingProductImageDao().insert(
                MissingProductImageEntity(
                    ean = ean,
                    companyId = resolveCompanyId(),
                    deviceId = deviceId,
                    firstReportedAtEpochMs = System.currentTimeMillis(),
                    uploadedAtEpochMs = null,
                ),
            )
        }
    }

    /**
     * Verifica, em lote, se alguma imagem antes ausente já foi cadastrada pelo backend (após um
     * técnico resolver manualmente) e baixa/cacheia via [downloadProductImageIfNeeded] — mesmo
     * caminho de sempre, sem duplicar lógica. Roda inteiramente em background: não é chamado do
     * fluxo de consulta de preço, então nunca atrasa um scan.
     */
    suspend fun checkAndRecoverMissingImages(limit: Int = 25): Int = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext 0

        val eans = runCatching { db.missingProductImageDao().getEansToRecheck(limit) }.getOrNull().orEmpty()
        if (eans.isEmpty()) return@withContext 0

        val eanFilter = eans.joinToString(",") { "\"$it\"" }
        val url = "https://iurqddkuihjsmxubibao.supabase.co/rest/v1/product_images_missing" +
            "?ean=in.($eanFilter)&resolved_at=not.is.null&select=ean,image_url"
        val req = Request.Builder()
            .url(url)
            .header("apikey", token)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val resolved = runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                JSONArray(resp.body?.string().orEmpty())
            }
        }.getOrNull() ?: return@withContext 0

        var recovered = 0
        val recoveredEans = mutableListOf<String>()
        for (i in 0 until resolved.length()) {
            val row = resolved.optJSONObject(i) ?: continue
            val ean = row.optString("ean", "").trim()
            val imageUrl = row.optString("image_url", "").trim()
            if (ean.isBlank() || imageUrl.isBlank()) continue
            val local = runCatching { downloadProductImageIfNeeded(ean = ean, rawUrl = imageUrl) }.getOrNull()
            if (local != null) {
                recoveredEans += ean
                recovered++
            }
        }
        if (recoveredEans.isNotEmpty()) {
            runCatching { db.missingProductImageDao().deleteResolved(recoveredEans) }
        }
        recovered
    }

    private fun fetchProductImageMeta(ean: String, step: PriceStep?): ProductImageMeta? {
        return runCatching {
            val resp =
                if (step != null) {
                    executeStep(step, JSONObject().put("ean", ean))
                } else {
                    val url = "http://srv-mupa.ddns.net:5050/produto-imagem/$ean"
                    val req = Request.Builder()
                        .url(url)
                        .header("accept", "application/json")
                        .get()
                        .build()
                    http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("product_image_http_${r.code}")
                        val text = r.body?.string().orEmpty()
                        JSONObject(text)
                    }
                }

            val imageUrl = normalizeExternalUrl(resp.optString("imagem_url", "").trim()).ifBlank { null }
            val signature = resp.optString("cor_assinatura_produto", "").trim().ifBlank { null }
            val legibility = resp.optString("fundo_legibilidade", "").trim().ifBlank { null }
            val light = resp.optString("cor_dominante_claro", "").trim().ifBlank { null }
            val dark = resp.optString("cor_dominante_escuro", "").trim().ifBlank { null }
            ProductImageMeta(
                imageUrl = imageUrl,
                signature = signature,
                legibility = legibility,
                light = light,
                dark = dark,
            )
        }.getOrNull()
    }

    private fun productsDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "products").apply { mkdirs() }
    }

    fun getDefaultProductImagePath(): String = defaultProductImagePath()

    fun getLocalProductImagePathIfExists(ean: String): String? = localProductImagePathIfExists(ean)

    private fun defaultProductImagePath(): String {
        val target = File(productsDir(), "produto_sem_imagem.webp")
        if (target.exists() && target.length() > 0L) return target.absolutePath

        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F1E7DA") }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        val margin = 54f
        val stroke = 18f
        val borderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#A3322A")
                style = Paint.Style.STROKE
                strokeWidth = stroke
            }
        canvas.drawRoundRect(
            RectF(margin, margin, size - margin, size - margin),
            18f,
            18f,
            borderPaint,
        )

        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#A3322A")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = 74f
            }

        val lines = listOf("PRODUTO", "SEM", "IMAGEM")
        val blockHeight = lines.size * (textPaint.textSize * 1.05f)
        var y = (size / 2f) - (blockHeight / 2f) + textPaint.textSize
        for (line in lines) {
            canvas.drawText(line, size / 2f, y, textPaint)
            y += textPaint.textSize * 1.05f
        }

        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            val fmt =
                @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            if (!bitmap.compress(fmt, 82, out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            runCatching { out.fd.sync() }
        }
        bitmap.recycle()

        if (target.exists()) runCatching { target.delete() }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            runCatching { tmp.delete() }
        }
        return target.absolutePath
    }

    private fun sanitizeLocalPathOrNull(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (!path.startsWith("/")) return null
        val f = File(path)
        if (!f.exists() || f.length() <= 0L) return null
        if (f.name.startsWith("produto_sem_imagem")) return null
        return f.absolutePath
    }

    private fun fetchVtexImageUrlFromApiProdutos(ean: String): String? {
        return runCatching {
            val url = "https://vsocztidewsdlzcongkz.supabase.co/functions/v1/api-produtos?ean=$ean"
            val req =
                Request.Builder()
                    .url(url)
                    .header("accept", "application/json")
                    .get()
                    .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty().trim()
                if (text.isBlank()) return null
                val root = JSONObject(text)
                val produto = root.optJSONObject("produto") ?: root
                val raw = produto.optString("imagem_url_vtex", "").trim()
                normalizeExternalUrl(raw).ifBlank { null }
            }
        }.getOrNull()
    }

    private fun localProductImagePathIfExists(ean: String): String? {
        val normalized = ean.trim()
        if (normalized.isBlank()) return null
        val primary = File(productsDir(), "$normalized.webp")
        if (primary.exists() && primary.length() > 0L) return primary.absolutePath

        val any =
            productsDir().listFiles()
                ?.firstOrNull { it.isFile && it.length() > 0L && it.nameWithoutExtension == normalized }
        return any?.absolutePath
    }

    /** Arquivo de imagem local do [ean], se existir (independente da idade). */
    private fun localProductImageFile(ean: String): File? {
        val normalized = ean.trim()
        if (normalized.isBlank()) return null
        val primary = File(productsDir(), "$normalized.webp")
        if (primary.exists() && primary.length() > 0L) return primary
        return productsDir().listFiles()
            ?.firstOrNull { it.isFile && it.length() > 0L && it.nameWithoutExtension == normalized }
    }

    /** true se há imagem local do [ean] e ela ainda está dentro da janela de 60 min. */
    private fun isProductImageFresh(ean: String): Boolean {
        val f = localProductImageFile(ean) ?: return false
        return System.currentTimeMillis() - f.lastModified() < IMAGE_CACHE_TTL_MS
    }

    /** true se há imagem local do [ean] mas ela já venceu (>60 min) — deve ser reatualizada. */
    fun isProductImageStale(ean: String): Boolean {
        val f = localProductImageFile(ean) ?: return false
        return System.currentTimeMillis() - f.lastModified() >= IMAGE_CACHE_TTL_MS
    }

    private fun normalizeCachedProduct(product: PriceProduct?): PriceProduct? {
        if (product == null) return null
        val sanitized = sanitizeLocalPathOrNull(product.image)
        if (sanitized != null) return product.copy(image = sanitized)
        val local = localProductImagePathIfExists(product.ean)
        return product.copy(image = local)
    }

    private fun downloadProductImageIfNeeded(
        ean: String,
        rawUrl: String,
        headers: Map<String, String>? = null
    ): String? {
        val url = normalizeExternalUrl(rawUrl).trim()
        if (url.isBlank()) return null
        val now = System.currentTimeMillis()
        val optimizedTarget = File(productsDir(), "$ean.webp")
        if (optimizedTarget.exists() && optimizedTarget.length() > 0L) {
            // Cache 60 min: só reaproveita se ainda estiver fresco; vencido, apaga e rebaixa.
            if (now - optimizedTarget.lastModified() < IMAGE_CACHE_TTL_MS) return optimizedTarget.absolutePath
            runCatching { optimizedTarget.delete() }
        }

        val existingAny =
            productsDir().listFiles()
                ?.firstOrNull { it.isFile && it.length() > 0L && it.nameWithoutExtension == ean }
        // Arquivo não-otimizado vencido também é descartado pra forçar download novo.
        if (existingAny != null && now - existingAny.lastModified() >= IMAGE_CACHE_TTL_MS) {
            runCatching { existingAny.delete() }
        }
        val existing = existingAny?.takeIf { it.exists() && it.length() > 0L }
        if (existing != null) {
            val bmp = runCatching { BitmapFactory.decodeFile(existing.absolutePath) }.getOrNull()
            if (bmp == null) return existing.absolutePath

            val w = bmp.width.coerceAtLeast(1)
            val h = bmp.height.coerceAtLeast(1)
            val maxDim = 512
            val scale =
                if (w <= maxDim && h <= maxDim) {
                    1f
                } else {
                    val s = maxDim.toFloat() / maxOf(w, h).toFloat()
                    s.coerceIn(0.1f, 1f)
                }
            val outW = (w * scale).toInt().coerceAtLeast(1)
            val outH = (h * scale).toInt().coerceAtLeast(1)
            val scaled = if (outW == w && outH == h) bmp else Bitmap.createScaledBitmap(bmp, outW, outH, true)
            if (scaled !== bmp) bmp.recycle()

            val tmpOptimized = File(productsDir(), "$ean.webp.tmp")
            FileOutputStream(tmpOptimized).use { out ->
                val fmt =
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                if (!scaled.compress(fmt, 82, out)) {
                    scaled.compress(Bitmap.CompressFormat.JPEG, 86, out)
                }
                runCatching { out.fd.sync() }
            }
            scaled.recycle()

            if (tmpOptimized.exists() && tmpOptimized.length() > 0L) {
                if (optimizedTarget.exists()) runCatching { optimizedTarget.delete() }
                if (!tmpOptimized.renameTo(optimizedTarget)) {
                    tmpOptimized.copyTo(optimizedTarget, overwrite = true)
                    runCatching { tmpOptimized.delete() }
                }
                runCatching { existing.delete() }
                return optimizedTarget.absolutePath
            }
            runCatching { tmpOptimized.delete() }
            return existing.absolutePath
        }

        val tmpDownload = File(productsDir(), "$ean.download.tmp")
        val reqBuilder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> reqBuilder.header(k, v) }
        val req = reqBuilder.get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("product_image_download_http_${resp.code}")
            val body = resp.body ?: throw IllegalStateException("product_image_empty_body")
            FileOutputStream(tmpDownload).use { out ->
                body.byteStream().use { input ->
                    input.copyTo(out)
                }
                runCatching { out.fd.sync() }
            }
        }

        if (!tmpDownload.exists() || tmpDownload.length() < 100L) {
            runCatching { tmpDownload.delete() }
            return null
        }

        val firstChar = runCatching { tmpDownload.bufferedReader().use { it.read() } }.getOrDefault(-1)
        if (firstChar == '{'.code || firstChar == '['.code) {
            runCatching { tmpDownload.delete() }
            return null
        }

        val bmp = runCatching { BitmapFactory.decodeFile(tmpDownload.absolutePath) }.getOrNull()
        if (bmp == null) {
            val clean = url.substringBefore('?').trim()
            val ext =
                clean.substringAfterLast('.', missingDelimiterValue = "")
                    .trim()
                    .lowercase()
                    .takeIf { it.length in 2..5 }
                    ?.let { ".$it" }
                    ?: ".img"
            val rawTarget = File(productsDir(), ean + ext)
            if (rawTarget.exists()) runCatching { rawTarget.delete() }
            if (!tmpDownload.renameTo(rawTarget)) {
                tmpDownload.copyTo(rawTarget, overwrite = true)
                runCatching { tmpDownload.delete() }
            }
            return rawTarget.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        }

        val maxDim = 512
        val w = bmp.width.coerceAtLeast(1)
        val h = bmp.height.coerceAtLeast(1)
        val scale =
            if (w <= maxDim && h <= maxDim) {
                1f
            } else {
                val s = maxDim.toFloat() / maxOf(w, h).toFloat()
                s.coerceIn(0.1f, 1f)
            }
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        val scaled = if (outW == w && outH == h) bmp else Bitmap.createScaledBitmap(bmp, outW, outH, true)
        if (scaled !== bmp) bmp.recycle()

        val tmpOptimized = File(productsDir(), "$ean.webp.tmp")
        FileOutputStream(tmpOptimized).use { out ->
            val fmt =
                @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            if (!scaled.compress(fmt, 82, out)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 86, out)
            }
            runCatching { out.fd.sync() }
        }
        scaled.recycle()
        runCatching { tmpDownload.delete() }

        if (!tmpOptimized.exists() || tmpOptimized.length() == 0L) {
            runCatching { tmpOptimized.delete() }
            return null
        }
        if (optimizedTarget.exists()) runCatching { optimizedTarget.delete() }
        if (!tmpOptimized.renameTo(optimizedTarget)) {
            tmpOptimized.copyTo(optimizedTarget, overwrite = true)
            runCatching { tmpOptimized.delete() }
        }
        return optimizedTarget.takeIf { it.exists() && it.length() > 0L }?.absolutePath
    }

    private fun normalizeExternalUrl(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("`") && s.endsWith("`") && s.length >= 2) {
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            s = s.substring(1, s.length - 1).trim()
        }
        s = s.replace("`", "").trim()
        return s
    }

    private fun fetchAssaiSeqProduto(ean: String): Pair<String, String>? {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return null

        val url =
            "https://iurqddkuihjsmxubibao.supabase.co/rest/v1/seq_produto_assai?select=*&CODACESSONUM=eq.$ean"

        val req = Request.Builder()
            .url(url)
            .header("apikey", token)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("assai_supabase_http_${resp.code}")
            val body = resp.body?.string().orEmpty()
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            val first = arr.optJSONObject(0) ?: return null
            val seq = first.optString("SEQPRODUTO", "").trim()
            val desc = first.optString("DESCCOMPLETA", "").trim()
            if (seq.isBlank()) return null
            return seq to desc
        }
    }

    private fun fetchAssaiStock(seqProduto: String, storeId: Int): JSONObject {
        val url = "https://marketplace.assai.com.br/stock?id_product=$seqProduto&id_store=$storeId"
        val req = Request.Builder()
            .url(url)
            .header("accept", "application/json")
            .header("accept-language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("origin", "https://marketplace.assai.com.br")
            .header("referer", "https://marketplace.assai.com.br/")
            .header(
                "user-agent",
                "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            )
            .header("x-basicauthorization", "b3V0Ym91bmRAc3NhaUNvbXBhc3M6MWY1NzZjZGRkZWU3MzcwZTQwZWFkOWM2ZGZmMzM4NzY1MWIxN2FiMg")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("assai_marketplace_http_${resp.code}")
            val text = resp.body?.string().orEmpty().trim()
            return JSONObject(text)
        }
    }

    private fun parseProductFromCache(ean: String, cache: PriceCacheEntity?): PriceProduct? {
        if (cache == null) return null
        return runCatching {
            val o = JSONObject(cache.productJson)
            val packsArr = o.optJSONArray("packs") ?: JSONArray()
            val packs = ArrayList<PricePack>(packsArr.length())
            for (i in 0 until packsArr.length()) {
                val p = packsArr.optJSONObject(i) ?: continue
                val label = p.optString("label", "").ifBlank { null } ?: continue
                val price = p.optDouble("price", Double.NaN).takeIf { !it.isNaN() } ?: continue
                val unit = p.optDouble("unit_price", Double.NaN).takeIf { !it.isNaN() } ?: continue
                packs += PricePack(label = label, price = price, unitPrice = unit)
            }

            val themeObj = o.optJSONObject("theme")
            val theme =
                themeObj?.let {
                    PriceTheme(
                        signature = it.optString("signature", "").ifBlank { null },
                        light = it.optString("light", "").ifBlank { null },
                        dark = it.optString("dark", "").ifBlank { null },
                    )
                }

            val offerObj = o.optJSONObject("offer")
            val offer =
                offerObj?.let {
                    PriceOffer(
                        enabled = it.optBoolean("enabled", false),
                        title = it.optString("title", "").ifBlank { null },
                        description = it.optString("description", "").ifBlank { null },
                        secondUnit = it.optDouble("second_unit", Double.NaN).takeIf { v -> !v.isNaN() },
                        type = it.optString("type", "").ifBlank { null },
                    )
                }

            val slotsArr = o.optJSONArray("price_slots")
            val priceSlots = if (slotsArr != null) {
                val list = ArrayList<ProductPriceSlot>(slotsArr.length())
                for (i in 0 until slotsArr.length()) {
                    val s = slotsArr.optJSONObject(i) ?: continue
                    val label = s.optString("label", "")
                    val value = s.optDouble("value", Double.NaN).takeIf { !it.isNaN() } ?: continue
                    val field = s.optString("field", "")
                    val isPromo = s.optBoolean("isPromo", false) || s.optBoolean("is_promo", false) || parseBoolean(s.opt("isPromo")) || parseBoolean(s.opt("is_promo"))
                    val isClub = s.optBoolean("isClub", false) || s.optBoolean("is_club", false) || parseBoolean(s.opt("isClub")) || parseBoolean(s.opt("is_club"))
                    list += ProductPriceSlot(label = label, value = value, field = field, isPromo = isPromo, isClub = isClub)
                }
                list
            } else {
                null
            }

            PriceProduct(
                id = o.optString("id", "").ifBlank { null },
                ean = ean,
                description = o.optString("description", "").ifBlank { null },
                price = o.optDouble("price", Double.NaN).takeIf { !it.isNaN() },
                originalPrice = o.optDouble("originalPrice", Double.NaN).takeIf { !it.isNaN() },
                clubPrice = o.optDouble("clubPrice", Double.NaN).takeIf { !it.isNaN() },
                pricePromotional = o.optDouble("pricePromotional", Double.NaN).takeIf { !it.isNaN() },
                priceClub = o.optDouble("priceClub", Double.NaN).takeIf { !it.isNaN() },
                priceWholesale = o.optDouble("priceWholesale", Double.NaN).takeIf { !it.isNaN() },
                priceWeighable = o.optDouble("priceWeighable", Double.NaN).takeIf { !it.isNaN() },
                priceFrom = o.optDouble("priceFrom", Double.NaN).takeIf { !it.isNaN() },
                cardPrice = o.optDouble("cardPrice", Double.NaN).takeIf { !it.isNaN() },
                stock = o.optInt("stock", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                image = o.optString("image", "").ifBlank { null },
                clientImageUrl = o.optString("clientImageUrl", "").ifBlank { o.optString("client_image_url", "").ifBlank { null } },
                offer = offer,
                packs = packs,
                theme = theme,
                offline = o.optBoolean("offline", false),
                priceSlots = priceSlots,
                xmlLayoutType = o.optString("xmlLayoutType", "").ifBlank { o.optString("xml_layout_type", "").ifBlank { null } },
            )
        }.getOrNull()
    }

    private fun executeStep(step: PriceStep, state: JSONObject): JSONObject {
        val url = interpolate(step.url, state)
        val method = step.method.uppercase()

        val reqBuilder = Request.Builder().url(url)
        step.headers.forEach { (k, v) -> reqBuilder.header(k, interpolate(v, state)) }

        if (method == "POST" || method == "PUT" || method == "PATCH") {
            val bodyStr = if (!step.body.isNullOrBlank()) {
                interpolate(step.body, state)
            } else {
                buildRequestBodyForStep(step, state).toString()
            }
            val body = bodyStr.toRequestBody("application/json".toMediaType())
            reqBuilder.method(method, body)
        } else {
            reqBuilder.get()
        }

        http.newCall(reqBuilder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e("MPlayerPrice", "HTTP Error ${resp.code}: $text")
                throw IllegalStateException("http_${resp.code} body=$text")
            }
            val trimmed = text.trim()
            return if (trimmed.startsWith("[")) {
                JSONObject().put("_array", JSONArray(trimmed))
            } else {
                JSONObject(trimmed)
            }
        }
    }

    private fun buildRequestBodyForStep(@Suppress("UNUSED_PARAMETER") step: PriceStep, state: JSONObject): JSONObject {
        val o = JSONObject()
        o.put("ean", state.optString("ean"))
        val serial = state.optString("device", "").ifBlank { deviceId }
        if (serial.isNotBlank()) {
            o.put("serial", serial)
        }
        val keys = listOf("internal_code", "SEQPRODUTO", "codigo_interno", "product_id")
        for (k in keys) {
            val v = state.optString(k, "")
            if (v.isNotBlank()) o.put(k, v)
        }
        return o
    }

    private fun findJsonArrayRecursively(json: Any?, key: String): JSONArray? {
        if (json is JSONArray) {
            for (i in 0 until json.length()) {
                val found = findJsonArrayRecursively(json.opt(i), key)
                if (found != null) return found
            }
        } else if (json is JSONObject) {
            if (json.has(key)) {
                val arr = json.optJSONArray(key)
                if (arr != null) return arr
            }
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val found = findJsonArrayRecursively(json.opt(k), key)
                if (found != null) return found
            }
        }
        return null
    }

    private fun applyMapping(resp: JSONObject, mapping: Map<String, String>, state: JSONObject) {
        mapping.forEach { (targetKey, source) ->
            val value = extractValue(resp, source)
            if (value != null) state.put(targetKey, value)
        }
        val autoSlots = findJsonArrayRecursively(resp, "price_slots")
        if (autoSlots != null) {
            state.put("price_slots", autoSlots)
        }
    }

    private fun extractValue(resp: JSONObject, source: String): Any? {
        val s = source.trim()
        if (s.isBlank()) return null
        if (s.contains(".")) {
            var cur: Any = resp
            val parts = s.split(".")
            for (p in parts) {
                if (cur is JSONObject) {
                    cur = cur.opt(p) ?: return null
                } else {
                    return null
                }
            }
            return cur
        }

        if (resp.has(s)) return resp.opt(s)
        val arr = resp.optJSONArray("_array")
        if (arr != null && arr.length() > 0) {
            val first = arr.optJSONObject(0)
            if (first != null && first.has(s)) return first.opt(s)
        }
        // Não é um path de resposta (ex: "multi_price", "true") - tratar como valor literal do mapping.
        return s
    }

    private fun buildFinalProduct(state: JSONObject): PriceProduct? {
        val ean = state.optString("ean", "").trim()
        if (ean.isBlank()) return null

        val desc = state.optString("description", "").ifBlank { state.optString("DESCCOMPLETA", "").ifBlank { null } }
        val price = state.optDouble("price", Double.NaN).takeIf { !it.isNaN() }
        val originalPrice = state.optDouble("originalPrice", Double.NaN).takeIf { !it.isNaN() }
        val clubPrice = state.optDouble("clubPrice", Double.NaN).takeIf { !it.isNaN() }

        val pricePromotional = state.optDouble("price_promotional", Double.NaN).takeIf { !it.isNaN() }
            ?: state.optDouble("pricePromotional", Double.NaN).takeIf { !it.isNaN() }
        val priceClub = state.optDouble("price_club", Double.NaN).takeIf { !it.isNaN() }
            ?: state.optDouble("priceClub", Double.NaN).takeIf { !it.isNaN() }
            ?: clubPrice
        val priceWholesale = state.optDouble("price_wholesale", Double.NaN).takeIf { !it.isNaN() }
            ?: state.optDouble("priceWholesale", Double.NaN).takeIf { !it.isNaN() }
        val priceWeighable = state.optDouble("price_weighable", Double.NaN).takeIf { !it.isNaN() }
            ?: state.optDouble("priceWeighable", Double.NaN).takeIf { !it.isNaN() }
        val priceFrom = state.optDouble("price_from", Double.NaN).takeIf { !it.isNaN() }
            ?: state.optDouble("priceFrom", Double.NaN).takeIf { !it.isNaN() }
            ?: originalPrice

        val slotsArr = state.optJSONArray("price_slots")
        val priceSlots = if (slotsArr != null) {
            val list = ArrayList<ProductPriceSlot>(slotsArr.length())
            for (i in 0 until slotsArr.length()) {
                val s = slotsArr.optJSONObject(i) ?: continue
                val label = s.optString("label", "")
                val value = s.optDouble("value", Double.NaN).takeIf { !it.isNaN() } ?: continue
                val field = s.optString("field", "")
                val isPromo = s.optBoolean("isPromo", false) || s.optBoolean("is_promo", false) || parseBoolean(s.opt("isPromo")) || parseBoolean(s.opt("is_promo"))
                val isClub = s.optBoolean("isClub", false) || s.optBoolean("is_club", false) || parseBoolean(s.opt("isClub")) || parseBoolean(s.opt("is_club"))
                list += ProductPriceSlot(label = label, value = value, field = field, isPromo = isPromo, isClub = isClub)
            }
            list
        } else {
            null
        }

        var resolvedPrice = price
        var resolvedOriginalPrice = originalPrice
        var resolvedClubPrice = clubPrice

        if (!priceSlots.isNullOrEmpty()) {
            val promoSlot = priceSlots.firstOrNull { it.isPromo && !it.isClub }
            val normalSlot = priceSlots.firstOrNull { !it.isPromo && !it.isClub }
            val clubSlot = priceSlots.firstOrNull { it.isClub }

            resolvedPrice = promoSlot?.value ?: normalSlot?.value ?: priceSlots.first().value

            val normalVal = normalSlot?.value
            if (normalVal != null && normalVal > resolvedPrice) {
                resolvedOriginalPrice = normalVal
            }

            resolvedClubPrice = clubSlot?.value ?: resolvedClubPrice
        }

        val stock = state.optInt("stock", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val image = state.optString("image", "").ifBlank { null }
        val clientImageUrl = state.optString("client_image_url", "").ifBlank {
            state.optString("clientImageUrl", "").ifBlank {
                if (image?.startsWith("http") == true) image else null
            }
        }

        if (desc == null && resolvedPrice == null && image == null) return null

        val offerObj = state.optJSONObject("offer")
        val offerEnabled =
            offerObj?.optBoolean("enabled", false)
                ?: parseBoolean(state.opt("offer_enabled"))
        val offerTitle =
            offerObj?.optString("title", "")?.ifBlank { null }
                ?: state.optString("offer_title", "").ifBlank { null }
        val offerDescription =
            offerObj?.optString("description", "")?.ifBlank { null }
                ?: state.optString("offer_description", "").ifBlank { null }
        val offerType =
            offerObj?.optString("type", "")?.ifBlank { null }
                ?: state.optString("offer_type", "").ifBlank { null }
        val offerSecondUnit =
            (offerObj?.optDouble("second_unit", Double.NaN) ?: Double.NaN).takeIf { v -> !v.isNaN() }
                ?: state.optDouble("offer_second_unit", Double.NaN).takeIf { v -> !v.isNaN() }

        val offer =
            if (offerEnabled || offerTitle != null || offerDescription != null || offerSecondUnit != null) {
                PriceOffer(
                    enabled = true,
                    title = offerTitle,
                    description = offerDescription,
                    secondUnit = offerSecondUnit,
                    type = offerType,
                )
            } else {
                null
            }

        val cardPrice = state.optDouble("card_price", Double.NaN).takeIf { !it.isNaN() }
            ?: state.optDouble("cardPrice", Double.NaN).takeIf { !it.isNaN() }
            ?: state.optDouble("PRECO_CRM", Double.NaN).takeIf { !it.isNaN() }
        val xmlLayoutType = state.optString("xml_layout_type", "").ifBlank { state.optString("xmlLayoutType", "").ifBlank { null } }

        return PriceProduct(
            id = state.optString("id", "").ifBlank { state.optString("sku", "").ifBlank { null } },
            ean = ean,
            description = desc,
            price = resolvedPrice,
            originalPrice = resolvedOriginalPrice,
            clubPrice = resolvedClubPrice,
            pricePromotional = pricePromotional ?: (if (resolvedPrice != resolvedOriginalPrice) resolvedPrice else null),
            priceClub = priceClub ?: resolvedClubPrice,
            priceWholesale = priceWholesale,
            priceWeighable = priceWeighable,
            priceFrom = priceFrom ?: resolvedOriginalPrice,
            cardPrice = cardPrice,
            stock = stock,
            image = image,
            clientImageUrl = clientImageUrl,
            offer = offer,
            packs = emptyList(),
            theme = null,
            offline = false,
            priceSlots = priceSlots,
            xmlLayoutType = xmlLayoutType,
        )
    }

    private fun productToJson(product: PriceProduct): JSONObject {
        val packs = JSONArray()
        product.packs.forEach { p ->
            packs.put(
                JSONObject()
                    .put("label", p.label)
                    .put("price", p.price)
                    .put("unit_price", p.unitPrice),
            )
        }

        val theme =
            product.theme?.let {
                JSONObject()
                    .put("signature", it.signature)
                    .put("light", it.light)
                    .put("dark", it.dark)
            }

        val offer =
            product.offer?.let {
                JSONObject()
                    .put("enabled", it.enabled)
                    .put("title", it.title)
                    .put("description", it.description)
                    .put("second_unit", it.secondUnit)
                    .put("type", it.type)
            }

        val priceSlots = JSONArray()
        product.priceSlots?.forEach { s ->
            priceSlots.put(
                JSONObject()
                    .put("label", s.label)
                    .put("value", s.value)
                    .put("field", s.field)
                    .put("isPromo", s.isPromo)
                    .put("isClub", s.isClub)
            )
        }

        return JSONObject()
            .put("id", product.id)
            .put("ean", product.ean)
            .put("description", product.description)
            .put("price", product.price)
            .put("originalPrice", product.originalPrice)
            .put("clubPrice", product.clubPrice)
            .put("pricePromotional", product.pricePromotional)
            .put("priceClub", product.priceClub)
            .put("priceWholesale", product.priceWholesale)
            .put("priceWeighable", product.priceWeighable)
            .put("priceFrom", product.priceFrom)
            .put("cardPrice", product.cardPrice)
            .put("stock", product.stock)
            .put("image", product.image)
            .put("clientImageUrl", product.clientImageUrl)
            .put("offer", offer)
            .put("packs", packs)
            .put("theme", theme)
            .put("offline", product.offline)
            .put("price_slots", priceSlots)
            .put("xmlLayoutType", product.xmlLayoutType)
    }

    private fun parseBoolean(v: Any?): Boolean {
        return when (v) {
            is Boolean -> v
            is Int -> v != 0
            is Long -> v != 0L
            is Double -> v != 0.0
            is String -> {
                val s = v.trim()
                s.equals("true", true) || s == "1" || s.equals("yes", true) || s.equals("sim", true)
            }

            else -> false
        }
    }

    private fun interpolate(template: String, state: JSONObject): String {
        var out = template

        // 1. Suporte a ${key}
        val regexStandard = "\\$\\{([^}]+)\\}".toRegex()
        regexStandard.findAll(out).forEach { m ->
            val key = m.groupValues[1]
            val value = state.optString(key, "")
            out = out.replace(m.value, value)
        }

        // 2. Suporte a {{key}}
        val regexDoubleCurly = "\\{\\{([^}]+)\\}\\}".toRegex()
        regexDoubleCurly.findAll(out).forEach { m ->
            val key = m.groupValues[1]
            val value = state.optString(key, "")
            out = out.replace(m.value, value)
        }

        // 3. Suporte a %7B%7Bkey%7D%7D (URL-encoded {{key}})
        val regexEncodedDoubleCurly = "%7B%7B([^%]+)%7D%7D".toRegex()
        regexEncodedDoubleCurly.findAll(out).forEach { m ->
            val key = m.groupValues[1]
            val value = state.optString(key, "")
            out = out.replace(m.value, value)
        }

        return out
    }

    // ─────────────────────────────────────────────────────────────
    // integra-komprao  (Komprão / SuperKoch OnWay API)
    // Auth:  POST https://apionway.superkoch.com.br/on-way/auth/login
    //        body: {"username":"MUPA.APP","password":"vRTz834/"}
    //        resp: { data: { token: "..." } }
    // Price: GET  https://apionway.superkoch.com.br/on-way/product/ean/{ean}/store/{store}
    //        resp: { data: { PRECO_NORMAL, PRECO_PDV, PRECO_CLUBE_KOCH,
    //                        PRECO_PDV_ATACADO, PRECO_LEVEPAGUE,
    //                        priceDynamicsType, description, ean[] } }
    // ─────────────────────────────────────────────────────────────

    /** Token do Komprão (cache de 50 min). Faz login se necessário. */
    private fun obterTokenKomprao(): String {
        val now = System.currentTimeMillis()
        val cachedToken = sessionState.optString("komprao_access_token", "")
        val cachedAt = sessionState.optLong("komprao_access_token_at", 0L)
        if (cachedToken.isNotBlank() && (now - cachedAt) < 50 * 60 * 1000L) return cachedToken
        val authBody = """{"username":"MUPA.APP","password":"vRTz834/"}"""
        val authReq = Request.Builder()
            .url("https://apionway.superkoch.com.br/on-way/auth/login")
            .header("Content-Type", "application/json")
            .post(authBody.toRequestBody("application/json".toMediaType()))
            .build()
        val token = runCatching {
            httpMultiStep.newCall(authReq).execute().use { r ->
                JSONObject(r.body?.string().orEmpty())
            }.optJSONObject("data")?.optString("token", "").orEmpty()
        }.getOrDefault("")
        if (token.isNotBlank()) {
            sessionState.put("komprao_access_token", token)
            sessionState.put("komprao_access_token_at", now)
        }
        return token
    }

    private suspend fun queryKomprao(ean: String, config: PriceConfig, isOnline: Boolean, filial: String): PriceProduct? {
        val now = System.currentTimeMillis()
        val cache = db.priceCacheDao().getByEan(ean)
        val fromCache = cache != null && (now - cache.updatedAtEpochMs) <= config.cacheMinutes * 60 * 1000L

        if (fromCache) {
            val product = normalizeCachedProduct(parseProductFromCache(ean, cache))
            insertEvent(ean = ean, filial = filial, responseTimeMs = 0L, fromCache = true, success = product != null)
            return product
        }

        if (!isOnline) {
            val product = normalizeCachedProduct(parseProductFromCache(ean, cache))?.copy(offline = true)
            insertEvent(ean = ean, filial = filial, responseTimeMs = 0L, fromCache = false, success = product != null)
            return product
        }

        val startedAt = System.currentTimeMillis()
        var product: PriceProduct? = null
        try {
            // 1. Token (cache 50 min)
            val bearerToken = obterTokenKomprao()

            if (bearerToken.isBlank()) {
                Log.w("MPlayerPrice", "komprao_auth_failed ean=$ean")
                return null
            }

            // 2. Consulta de preço
            val store = filial.ifBlank { "18" }
            val priceResp = fetchKompraoPrice(ean = ean, store = store, bearerToken = bearerToken)

            // 3. Parse
            product = parseKompraoResponse(ean = ean, json = priceResp)?.let { attachLocalImageIfExists(it) }

            if (product != null) {
                db.priceCacheDao().upsert(
                    PriceCacheEntity(
                        ean = ean,
                        productJson = productToJson(product).toString(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }
        } catch (t: Throwable) {
            Log.w("MPlayerPrice", "query_komprao_failed ean=$ean err=${t.javaClass.simpleName}:${t.message}")
        } finally {
            val took = System.currentTimeMillis() - startedAt
            insertEvent(ean = ean, filial = filial, responseTimeMs = took, fromCache = false, success = product != null)
        }
        return product
    }

    private fun fetchKompraoPrice(ean: String, store: String, bearerToken: String): JSONObject {
        val url = "https://apionway.superkoch.com.br/on-way/product/ean/$ean/store/$store"
        val req = Request.Builder()
            .url(url)
            .header("accept", "application/json")
            .header("Authorization", "Bearer $bearerToken")
            .build()
        httpMultiStep.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.d("MPlayerPrice", "komprao_price_resp ean=$ean status=${resp.code} body=${body.take(400)}")
            return runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        }
    }

    private fun parseKompraoResponse(ean: String, json: JSONObject): PriceProduct? {
        val data = json.optJSONObject("data") ?: return null

        val description = data.optString("description", "").ifBlank {
            data.optString("fullDescription", "").ifBlank { null }
        }

        // ── Fonte da verdade: o array "prices" (o mesmo usado para imprimir a etiqueta).
        // Cada item traz { description, value, label, a_partir_de_x }. A label já vem pronta
        // da API (ex: "OFERTA", "Oferta Clube K:", "A partir de {{a_partir_de_x}} unidades…")
        // e o {{a_partir_de_x}} é interpolado com a quantidade retornada. ──
        val pricesArr = data.optJSONArray("prices") ?: return null

        val slots = ArrayList<ProductPriceSlot>()
        val precos = LinkedHashMap<String, Double>()
        var normal: Double? = null
        var pdv: Double? = null
        var atacado: Double? = null
        var clube: Double? = null

        for (i in 0 until pricesArr.length()) {
            val o = pricesArr.optJSONObject(i) ?: continue
            val desc = o.optString("description", "").trim().uppercase()
            val value = o.opt("value")?.toString()?.trim()?.toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            if (desc.isBlank()) continue
            precos[desc] = value

            // Label da API, com {{a_partir_de_x}} substituído pela quantidade
            val aPartirDe = o.opt("a_partir_de_x")?.toString()?.trim()?.toIntOrNull()
            val label = o.optString("label", "").trim()
                .replace("{{a_partir_de_x}}", aPartirDe?.toString().orEmpty())
                .trim()
                .removeSuffix(":")
                .trim()

            // Cor/estilo pelo tipo de preço
            val isClube = desc.contains("CLUBE")
            val isOferta = !isClube && desc != "PRECO_NORMAL" &&
                !label.equals("Preço Normal", ignoreCase = true)

            when (desc) {
                "PRECO_NORMAL" -> normal = value
                "PRECO_PDV" -> pdv = value
                "PRECO_PDV_ATACADO" -> atacado = value
                else -> if (isClube) clube = value
            }

            slots += ProductPriceSlot(
                label = label.ifBlank { "PREÇO" },
                value = value,
                field = when {
                    isClube -> "price_club"
                    isOferta -> "price_wholesale"
                    else -> "price"
                },
                isPromo = isOferta,
                isClub = isClube,
            )
        }
        if (slots.isEmpty()) return null

        // Preço de venda de referência (o "cheio" do dia)
        val precoVenda = pdv ?: normal ?: precos.values.first()

        // Mostra o preço normal riscado dentro do box das ofertas mais baratas
        val referencia = normal ?: pdv
        if (referencia != null) {
            for (i in slots.indices) {
                val s = slots[i]
                if ((s.isPromo || s.isClub) && s.value < referencia) {
                    slots[i] = s.copy(fromValue = referencia)
                }
            }
        }

        val localImagePath = localProductImagePathIfExists(ean)

        return PriceProduct(
            id = data.optString("sku", "").ifBlank { null },
            ean = ean,
            description = description,
            price = precoVenda,
            originalPrice = null,
            clubPrice = clube,
            priceClub = clube,
            priceWholesale = atacado,
            pricePromotional = if (normal != null && pdv != null && pdv < normal) pdv else null,
            priceFrom = null,
            priceWeighable = null,
            cardPrice = null,
            stock = null,
            image = localImagePath,
            clientImageUrl = null,
            offer = null,
            packs = emptyList(),
            theme = null,
            offline = false,
            priceSlots = slots.ifEmpty { null },
            xmlLayoutType = "multi_price",
        )
    }

    // ─────────────────────────────────────────────────────────────
    // integra-tcserver  (Gertec TCServer — protocolo TCP de terminal
    // de consulta, porta padrão 6500)
    //
    // Handshake:  conecta → servidor envia "#ok"
    //             terminal envia "#<tipo>|<versão>"
    //             servidor envia "#alwayslive"
    //             terminal envia "#alwayslive_ok"
    // Consulta:   terminal envia "#<ean>"
    //             servidor responde "#<DESCRIÇÃO>|<PREÇO>" ou "#nfound"
    //
    // Endereço configurado em Configurações (SettingsManager
    // tc_server_address, formato "ip:porta"; porta default 6500).
    // ─────────────────────────────────────────────────────────────

    private suspend fun queryTcServer(ean: String, config: PriceConfig, filial: String): PriceProduct? {
        val now = System.currentTimeMillis()
        val cache = db.priceCacheDao().getByEan(ean)
        val fromCache = cache != null && (now - cache.updatedAtEpochMs) <= config.cacheMinutes * 60 * 1000L

        if (fromCache) {
            val product = normalizeCachedProduct(parseProductFromCache(ean, cache))
            insertEvent(ean = ean, filial = filial, responseTimeMs = 0L, fromCache = true, success = product != null)
            return product
        }

        val settings = SettingsManager(context)
        val address = settings.getTcServerAddress()
        if (address.isBlank()) {
            Log.w("MPlayerPrice", "tcserver_address_not_configured ean=$ean")
            return null
        }
        val gertec = settings.getTcServerGertecEnabled()
        // address = "ip:porta" (ex: 192.168.1.175:8080). No modo Gertec, se o operador não
        // informar a porta, assume a padrão do TCServer Gertec (7476).
        var baseAddr = address.removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (gertec && !baseAddr.substringAfterLast(':', "").matches(Regex("""\d{1,5}"""))) {
            baseAddr = "$baseAddr:7476"
        }

        val startedAt = System.currentTimeMillis()
        var product: PriceProduct? = null
        try {
            product = if (gertec) {
                val html = fetchGertecBarcode(baseAddr, ean)
                Log.d("MPlayerPrice", "tcserver_gertec_resp ean=$ean len=${html?.length ?: -1}")
                html?.let { parseGertecResponse(ean, it) }?.let { attachLocalImageIfExists(it) }
            } else {
                val json = fetchTcServerPrice(baseAddr, ean)
                Log.d("MPlayerPrice", "tcserver_resp ean=$ean resp=${json?.toString()?.take(300)}")
                json?.let { parseTcServerResponse(ean, it) }?.let { attachLocalImageIfExists(it) }
            }

            if (product != null) {
                db.priceCacheDao().upsert(
                    PriceCacheEntity(
                        ean = ean,
                        productJson = productToJson(product).toString(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }
        } catch (t: Throwable) {
            Log.w("MPlayerPrice", "query_tcserver_failed ean=$ean addr=$baseAddr err=${t.javaClass.simpleName}:${t.message}")
            // Fallback para cache mesmo expirado quando o servidor está inacessível
            if (cache != null) {
                product = normalizeCachedProduct(parseProductFromCache(ean, cache))?.copy(offline = true)
            }
        } finally {
            val took = System.currentTimeMillis() - startedAt
            insertEvent(ean = ean, filial = filial, responseTimeMs = took, fromCache = false, success = product != null)
        }
        return product
    }

    /** GET http://{addr}/consulta?ean={ean} — retorna o JSON do TCServer Mupa (ou null se 404). */
    private fun fetchTcServerPrice(addr: String, ean: String): JSONObject? {
        val url = "http://$addr/consulta?ean=$ean"
        val req = Request.Builder().url(url).header("accept", "application/json").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 404 = produto não encontrado (resposta esperada, não é erro de rede)
                Log.d("MPlayerPrice", "tcserver_http_status=${resp.code} ean=$ean")
                return null
            }
            return runCatching { JSONObject(body) }.getOrNull()
        }
    }

    private fun parseTcServerResponse(ean: String, json: JSONObject): PriceProduct? {
        if (!json.optBoolean("encontrado", false)) return null

        fun num(key: String): Double? =
            if (json.isNull(key)) null else json.optDouble(key, -1.0).takeIf { it > 0 }

        val preco = num("preco") ?: return null
        val precoPromo = num("preco_promo")?.takeIf { it < preco }
        val precoClube = num("preco_clube")?.takeIf { it < preco }
        val description = json.optString("descricao", "").ifBlank { null }

        // Slots na ordem: PREÇO NORMAL → EM PROMOÇÃO → CLUBE
        val slots = ArrayList<ProductPriceSlot>()
        slots += ProductPriceSlot(
            label = "PREÇO NORMAL",
            value = preco,
            field = "price",
            isPromo = false,
            isClub = false,
        )
        if (precoPromo != null) {
            slots += ProductPriceSlot(
                label = "EM PROMOÇÃO",
                value = precoPromo,
                field = "price_wholesale",
                isPromo = true,
                isClub = false,
                fromValue = preco,  // "de" riscado dentro do box de promoção
            )
        }
        if (precoClube != null) {
            slots += ProductPriceSlot(
                label = "CLUBE",
                value = precoClube,
                field = "price_club",
                isPromo = false,
                isClub = true,
            )
        }

        // ── Campos extras dinâmicos vindos do TCServer Mupa ──
        // preços viram slots (com "de" riscado dentro do box); ofertas viram frases
        val ofertaFrases = ArrayList<String>()
        val extrasArr = json.optJSONArray("extras")
        if (extrasArr != null) {
            for (i in 0 until extrasArr.length()) {
                val ex = extrasArr.optJSONObject(i) ?: continue
                val tipo = ex.optString("tipo")
                val label = ex.optString("label").trim()
                when (tipo) {
                    "preco_simples" -> {
                        val v = ex.optDouble("valor", -1.0).takeIf { it > 0 } ?: continue
                        slots += ProductPriceSlot(
                            label = label.ifBlank { "PREÇO" },
                            value = v,
                            field = "price_extra",
                            isPromo = v < preco,
                            isClub = false,
                        )
                    }
                    "de_por" -> {
                        val de = ex.optDouble("de", -1.0).takeIf { it > 0 }
                        val por = ex.optDouble("por", -1.0).takeIf { it > 0 } ?: continue
                        slots += ProductPriceSlot(
                            label = label.ifBlank { "OFERTA" },
                            value = por,
                            field = "price_wholesale",
                            isPromo = true,
                            isClub = false,
                            fromValue = de?.takeIf { it > por },  // "de" riscado dentro do box
                        )
                    }
                    "leve_pague" -> {
                        val leve = ex.optInt("leve", 0)
                        val pague = ex.optInt("pague", 0)
                        if (leve > 0 && pague > 0) {
                            ofertaFrases += label.ifBlank { "LEVE $leve PAGUE $pague" }
                        }
                    }
                    "compre_pague" -> {
                        val qtd = ex.optInt("qtd", 0)
                        val valor = ex.optDouble("valor", -1.0).takeIf { it > 0 }
                        if (qtd > 0 && valor != null) {
                            ofertaFrases += label.ifBlank {
                                "NA COMPRA DE $qtd, PAGUE ${formatMoneyBr(valor)}"
                            }
                        }
                    }
                    "desconto_progressivo" -> {
                        val item = ex.optInt("item", 0)
                        val percent = ex.optDouble("percent", -1.0).takeIf { it > 0 }
                        if (item > 0 && percent != null) {
                            ofertaFrases += label.ifBlank {
                                "NO ${item}º ITEM, ${percent.toInt()}% DE DESCONTO"
                            }
                        }
                    }
                }
            }
        }

        val offer = if (ofertaFrases.isNotEmpty()) {
            PriceOffer(
                enabled = true,
                title = ofertaFrases.joinToString("  •  "),
                description = null,
                secondUnit = null,
                type = "extra",
            )
        } else null

        val localImagePath = localProductImagePathIfExists(ean)

        return PriceProduct(
            id = null,
            ean = ean,
            description = description,
            price = preco,
            originalPrice = null,
            clubPrice = precoClube,
            priceClub = precoClube,
            priceWholesale = precoPromo,
            pricePromotional = precoPromo,
            priceFrom = null,
            priceWeighable = null,
            cardPrice = null,
            stock = null,
            image = localImagePath,
            clientImageUrl = null,
            offer = offer,
            packs = emptyList(),
            theme = null,
            offline = false,
            priceSlots = slots,
            xmlLayoutType = "multi_price",
        )
    }

    private fun formatMoneyBr(v: Double): String =
        "R$ " + String.format(Locale.US, "%.2f", v).replace(".", ",")

    // ─────────────────────────────────────────────────────────────
    // TCServer da Gertec — protocolo HTTP nativo do "Terminal de Consulta"
    //
    // Consulta:  GET http://{addr}/barcode?param={ean}
    // Achou:     HTML (product.jsp) com os campos, separados por <br/>:
    //              Descricao: <desc>
    //              Label1: <label1>   /  Preco1: <price1>
    //              Label2: <label2>   /  Preco2: <price2>
    // Não achou: HTML (notfound.jsp) contendo 'nao foi encontrado'
    //
    // Porta padrão 7476 (confirmada pelo price_checker.xml distribuído
    // com o TCServer). Os rótulos LABEL1/LABEL2 são configurados no
    // próprio servidor da loja e chegam prontos na resposta.
    // ─────────────────────────────────────────────────────────────

    /** GET http://{addr}/barcode?param={ean} — retorna o HTML do TCServer Gertec (ou null em erro de rede). */
    private fun fetchGertecBarcode(addr: String, ean: String): String? {
        val url = "http://$addr/barcode?param=$ean"
        val req = Request.Builder().url(url).header("accept", "text/html").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.d("MPlayerPrice", "tcserver_gertec_http_status=${resp.code} ean=$ean")
                return null
            }
            return body
        }
    }

    /**
     * Converte preço em texto para Double, aceitando os dois formatos que o TCServer Gertec
     * pode emitir dependendo do cadastro: ponto decimal ("17.19", "1234.56") ou vírgula
     * decimal com ponto de milhar ("R$ 1.234,56", "6,79"). Retorna null se não houver número > 0.
     */
    private fun parseBrlNumber(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(Regex("[^0-9.,]"), "").trim()
        if (cleaned.isBlank()) return null
        // O separador decimal é sempre o último '.' ou ',' que aparecer; tudo antes é milhar.
        val lastSep = maxOf(cleaned.lastIndexOf('.'), cleaned.lastIndexOf(','))
        val normalized = if (lastSep < 0) {
            cleaned
        } else {
            val intPart = cleaned.substring(0, lastSep).replace(Regex("[.,]"), "")
            val decPart = cleaned.substring(lastSep + 1).replace(Regex("[^0-9]"), "")
            "$intPart.$decPart"
        }
        return normalized.toDoubleOrNull()?.takeIf { it > 0 }
    }

    private fun parseGertecResponse(ean: String, html: String): PriceProduct? {
        if (html.contains("nao foi encontrado", ignoreCase = true) ||
            html.contains("não foi encontrado", ignoreCase = true)
        ) {
            return null
        }

        fun field(name: String): String? =
            Regex("""$name\s*:\s*([^<\r\n]*)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.trim()?.ifBlank { null }

        val description = field("Descricao") ?: field("Descrição")
        val label1 = field("Label1")
        val label2 = field("Label2")
        val price1 = parseBrlNumber(field("Preco1") ?: field("Preço1")) ?: return null
        val price2 = parseBrlNumber(field("Preco2") ?: field("Preço2"))

        // LABEL1 costuma vir como placeholder genérico do servidor ("Price"). Para o preço
        // principal usamos sempre "PREÇO NORMAL"; um rótulo próprio só é respeitado se for
        // claramente específico (diferente de Price/Preço/Preço Normal).
        val label1Meaningful = label1?.takeIf {
            val n = it.trim().lowercase(Locale.getDefault())
            n.isNotBlank() && n != "price" && n != "preço" && n != "preco" &&
                n != "preço normal" && n != "preco normal"
        }
        val slots = ArrayList<ProductPriceSlot>()
        slots += ProductPriceSlot(
            label = label1Meaningful?.uppercase(Locale.getDefault()) ?: "PREÇO NORMAL",
            value = price1,
            field = "price",
            isPromo = false,
            isClub = false,
        )

        var precoPromo: Double? = null
        if (price2 != null && price2 != price1) {
            val cheaper = price2 < price1
            if (cheaper) precoPromo = price2
            slots += ProductPriceSlot(
                label = label2?.uppercase(Locale.getDefault()) ?: if (cheaper) "OFERTA" else "PREÇO",
                value = price2,
                field = if (cheaper) "price_wholesale" else "price_extra",
                isPromo = cheaper,
                isClub = false,
                fromValue = if (cheaper) price1 else null, // "de" riscado dentro do box de oferta
            )
        }

        return PriceProduct(
            id = null,
            ean = ean,
            description = description,
            price = price1,
            originalPrice = null,
            clubPrice = null,
            priceClub = null,
            priceWholesale = precoPromo,
            pricePromotional = precoPromo,
            priceFrom = null,
            priceWeighable = null,
            cardPrice = null,
            stock = null,
            image = localProductImagePathIfExists(ean),
            clientImageUrl = null,
            offer = null,
            packs = emptyList(),
            theme = null,
            offline = false,
            priceSlots = slots,
            xmlLayoutType = "multi_price",
        )
    }
}
