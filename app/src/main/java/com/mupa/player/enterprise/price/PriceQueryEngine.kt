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
import com.mupa.player.enterprise.managers.SponsoredLookupItem
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

    // Consulta Patrocinada (docs/2026-11-23-mplayer-android-sponsored-lookup.md): lista atual de
    // peças vindas do manifesto (atualizada a cada fetch/refresh via updateSponsoredLookups) e um
    // índice round-robin — cada consulta de preço avança pra próxima peça, ciclando.
    @Volatile
    private var sponsoredLookups: List<SponsoredLookupItem> = emptyList()
    private val sponsoredLookupIndex = java.util.concurrent.atomic.AtomicInteger(0)

    /** true quando o manifesto trouxe alguma peça de Consulta Patrocinada pra este dispositivo —
     * usado por [com.mupa.player.enterprise.ui.PlayerActivity] pra garantir que o fetch em
     * segundo plano (único lugar onde a prioridade da Consulta Patrocinada é aplicada, ver
     * [preloadProductImageAndTheme]) SEMPRE rode, mesmo quando o produto já tem arte publicitária
     * própria fresca em cache (achado real, 2026-11-24: esses produtos pulavam o fetch em
     * segundo plano inteiro — `needsBackgroundImageFetch=false` — e ficavam presos no layout de
     * arte por produto, nunca mostrando a peça patrocinada). */
    fun hasSponsoredLookupsConfigured(): Boolean = sponsoredLookups.isNotEmpty()

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

    // Client dedicado pro upload da foto + geração da arte no srv-mupa (roda em background via
    // requestArtGeneration, nunca no caminho síncrono da consulta de preço). O srv-mupa chama a
    // API Gemini de forma síncrona antes de responder, o que pode levar bem mais que os 5-8s dos
    // clients acima — por isso o timeout generoso aqui.
    private val httpArtUpload = TlsCompat.apply(OkHttpClient.Builder())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    // Client dedicado só pro lookup de imagem/arte genérico do srv-mupa (fetchProductImageMetaInner,
    // step=null) — achado real em campo (2026-11-13, filial UnidaSul): a rede de algumas lojas
    // bloqueia/redireciona esse servidor (2.25.239.153:5050) sem nunca responder, e os 4s do
    // client `http` acima atrasavam a foto de verdade (vinda da própria API do cliente) em TODO
    // scan. Pedido do usuário: timeout bem mais curto só pra essa checagem, que é um "bônus"
    // (arte publicitária), não o preço em si.
    private val httpImageMeta = TlsCompat.apply(OkHttpClient.Builder())
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()
    private val sessionState = JSONObject()

    // Escopo separado para gravações de analytics (insertEvent) que não devem atrasar o
    // retorno do preço para a UI - são "fire and forget".
    private val analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // Cache de imagem de produto: válido por 60 min. Passado isso, a imagem local ainda é
        // exibida na hora (rápido), mas um novo download roda em background pra atualizar.
        private const val IMAGE_CACHE_TTL_MS = 60L * 60L * 1000L

        // Consulta Patrocinada: o backend (Mupa Connect, docs/2026-11-23-mplayer-android-
        // sponsored-lookup.md) não envia uma região por peça (ao contrário de Telas
        // Patrocinadas, que tem `regiao` por campanha) — decisão do usuário (2026-11-24, 2ª
        // rodada): usar uma posição FIXA por enquanto, pensada pra caber na "área em branco"
        // que os criativos de referência (Unilever/Nestlé) reservam do lado direito da peça,
        // com margem pra não colar nos cantos nem no topo (onde costuma ter a faixa de
        // logo/marca). Ajustável aqui sem mexer em mais nada caso não caiba bem num criativo
        // real.
        private val SPONSORED_LOOKUP_DEFAULT_REGION = SponsorRegion(x0 = 0.32f, x1 = 0.96f, y0 = 0.14f, y1 = 0.92f)
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

    /**
     * Endpoint do serviço local de consulta de preços, configurado por dispositivo em
     * Configurações. Fica disponível para os templates do price_config como
     * {{price_host}} / {{price_port}} — assim uma integração local vira configuração no
     * manifest, sem código novo no motor.
     */
    private suspend fun resolvePriceEndpoint(): Pair<String, String> {
        val settings = runCatching { SettingsManager(context).getSettings() }.getOrNull()
        return (settings?.priceHost.orEmpty()) to (settings?.pricePort.orEmpty())
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
        val arteUrl: String?,
        val signature: String?,
        val legibility: String?,
        val light: String?,
        val dark: String?,
    )

    /** Região reservada (frações 0..1 da tela) onde o badge de preço deve ser desenhado por cima
     * de uma tela patrocinada — ver docs/SPEC_PATROCINIO_PRODUTOS_IMGS.md. Mesmo formato que a
     * arte vertical já usa hoje (caixaX0Pct/caixaX1Pct/caixaY0Pct/caixaY1Pct em PlayerActivity),
     * só que vindo do backend em vez de ser uma constante fixa no código. */
    data class SponsorRegion(
        val x0: Float,
        val x1: Float,
        val y0: Float,
        val y1: Float,
    )

    private data class SponsorCampaign(
        val imageUrl: String,
        val region: SponsorRegion,
        val themeSignature: String?,
        val campaignId: String,
        val expiresAtEpochMs: Long?,
    )

    /** Resultado de [preloadProductImageAndTheme]: caminho local da imagem baixada (crua ou
     * arte), tema de cores associado (se houver), e se essa imagem é a arte publicitária
     * gerada por IA (produtos-imgs/Gemini) — usado para decidir o badge de preço sobreposto. */
    data class PreloadedImage(
        val path: String?,
        val theme: PriceTheme?,
        val hasArt: Boolean = false,
        val sponsorRegion: SponsorRegion? = null,
        // Consulta Patrocinada (achado real de bancada, 2026-11-24, 2ª rodada): diferente de
        // Telas Patrocinadas (hasArt=true só mostra o badge de preço, a arte já traz nome/foto
        // embutidos), aqui a peça é um anúncio genérico da marca SEM nada do produto desenhado —
        // o app precisa compor foto+nome+preço por cima dela, dentro da área reservada
        // (sponsorRegion). `isSponsoredLookup` distingue esse modo de composição do modo
        // "só badge de preço" de Telas Patrocinadas, mesmo os dois usando hasArt=true.
        val isSponsoredLookup: Boolean = false,
        val productPhotoPath: String? = null,
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

        if (config.integration == "integra-unidasul") {
            return@withContext runCatching { queryUnidasul(normalizedEan, config, filial) }
                .onFailure {
                    Log.w(
                        "MPlayerPrice",
                        "query_failed integration=integra-unidasul ean=$normalizedEan err=${it.javaClass.simpleName}:${it.message}",
                    )
                }
                .getOrNull()
        }

        val startedAt = System.currentTimeMillis()
        val (priceHost, pricePort) = resolvePriceEndpoint()
        val state = JSONObject()
            .put("ean", normalizedEan)
            .put("device", deviceId)
            .put("filial", filial)
            .put("loja", filial)
            .put("store_id", filial)
            .put("integration", config.integration)
            .put("price_host", priceHost)
            .put("price_port", pricePort)

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
    ): PreloadedImage = withContext(Dispatchers.IO) {
        val normalizedEan = ean.trim()
        if (normalizedEan.isBlank()) return@withContext PreloadedImage(null, null, false)

        // Consulta Patrocinada (docs/2026-11-23-mplayer-android-sponsored-lookup.md): se o
        // manifesto trouxe peças pra alguma Tag deste dispositivo, elas têm prioridade sobre a
        // foto do produto — igual às Telas Patrocinadas por empresa (fallback logo abaixo, hoje
        // morto em produção porque o endpoint /patrocinio-ativo nunca existiu).
        //
        // 2ª rodada (achado real de bancada, 2026-11-24, referência visual do usuário): a peça
        // é um anúncio genérico da MARCA, mas o espaço reservado (a "área oval") é pra mostrar
        // foto+nome+preço REAIS do produto consultado por cima da peça — não do lado, nem
        // escondido. hasArt=true + sponsorRegion ativa o modo full-bleed (a peça ocupa a tela
        // toda, sem o split de painel esquerdo/direito) — mas, ao contrário de Telas
        // Patrocinadas (hasArt=true normal, que só desenha um badge de preço porque a arte já
        // traz nome/foto embutidos), aqui também precisamos da foto+nome reais: `isSponsoredLookup
        // = true` sinaliza pra updatePriceBadge compor os três juntos (ver ali).
        //
        // 3ª rodada (pedido do usuário, 2026-11-24: "precisa mostrar a imagem do produto"): ao
        // contrário da 1ª versão (só reaproveitava o cache, sem foto quando o produto nunca tinha
        // sido consultado), agora roda o fluxo normal INTEIRO (arte/VTEX/Komprão/Mupa — extraído
        // pra [resolveNormalPreloadedImage]) em paralelo, exatamente como se não houvesse
        // patrocínio — só pra conseguir uma foto de verdade pra compor dentro da área reservada.
        // O resultado dessa resolução nunca vira o que aparece em tela (a peça patrocinada sempre
        // vence como fundo); é usado só como fonte da foto do produto.
        val sponsoredAdPath = resolveActiveSponsoredLookup()
        val normalResult = resolveNormalPreloadedImage(normalizedEan, config, isOnline)
        if (sponsoredAdPath != null) {
            // Se o fluxo normal achou/gerou ARTE publicitária (hasArt=true), esse arquivo é uma
            // peça de campanha própria do produto (fundo+badge desenhados), não uma foto simples
            // — não serve de thumbnail. E se a checagem de rede (passo 0/1/2/3) falhar à toa (ex.
            // backend fora do ar), [resolveNormalPreloadedImage] descarta até uma foto antiga já
            // cacheada em disco (comportamento de sempre — reconsulta pra ver se a arte ficou
            // pronta, sem fallback pra foto velha). Pra Consulta Patrocinada essa foto é só
            // decorativa (o preço real continua vindo da consulta de verdade), então vale mais
            // mostrar uma foto desatualizada do que nenhuma: cai pro arquivo local já cacheado
            // (sem sufixo "_arte"), mesmo vencido, sempre que o fluxo normal não devolveu uma
            // foto plana utilizável.
            var plainProductPhoto = normalResult.path.takeIf { !normalResult.hasArt }
                ?: localProductImagePathIfExists(normalizedEan)
            // 4ª rodada (achado real de bancada, 2026-11-25): quando o produto já tem ARTE
            // publicitária fresca em cache (comum — qualquer EAN já consultado antes sem
            // patrocínio ganha uma), [resolveNormalPreloadedImage] retorna logo no passo 0 (arte)
            // e NUNCA chega aos passos 1-3 (foto crua Komprão/VTEX/Mupa) — então plainProductPhoto
            // fica null mesmo quando uma foto de verdade está a um fetch de distância. Busca ela
            // agora, só pra essa foto (nunca pra arte), reusando os mesmos passos 1-3 extraídos em
            // [fetchPlainProductPhoto].
            if (plainProductPhoto == null && isOnline) {
                plainProductPhoto = runCatching { fetchPlainProductPhoto(normalizedEan, config) }.getOrNull()
            }
            Log.d(
                "MPlayerSponsoredLookup",
                "resolve ean=$normalizedEan adPath=$sponsoredAdPath normalResultPath=${normalResult.path} " +
                    "normalResultHasArt=${normalResult.hasArt} plainProductPhoto=$plainProductPhoto",
            )
            return@withContext PreloadedImage(
                path = sponsoredAdPath,
                theme = null,
                hasArt = true,
                sponsorRegion = SPONSORED_LOOKUP_DEFAULT_REGION,
                isSponsoredLookup = true,
                productPhotoPath = plainProductPhoto,
            )
        }
        normalResult
    }

    /** Resolução "normal" (sem patrocínio) de foto/arte/tema por EAN — extraído de
     * [preloadProductImageAndTheme] pra poder rodar mesmo quando uma Consulta Patrocinada está
     * ativa (ver ali): o resultado vira a foto do produto a compor dentro da peça patrocinada,
     * em vez do que normalmente apareceria sozinho em tela. */
    private suspend fun resolveNormalPreloadedImage(
        normalizedEan: String,
        config: PriceConfig,
        isOnline: Boolean,
    ): PreloadedImage {
        // Telas Patrocinadas (Retail Media) por empresa/orientação — mantido como fallback, mas
        // sem efeito prático em produção hoje (ver resolveActiveSponsor: endpoint inexistente).
        val sponsorOrientacao = if (isDeviceVertical()) "vertical" else "horizontal"
        resolveActiveSponsor(sponsorOrientacao)?.let { (path, campaign) ->
            val sponsorTheme = campaign.themeSignature?.let { PriceTheme(signature = it, light = null, dark = null) }
            return PreloadedImage(path, sponsorTheme, hasArt = true, sponsorRegion = campaign.region)
        }

        val cachedProduct = db.priceCacheDao().getByEan(normalizedEan)?.let { cache ->
            runCatching { parseProductFromCache(normalizedEan, cache) }.getOrNull()
        }

        val existing = localProductImagePathIfExists(normalizedEan)
        val cachedHasArt = cachedProduct?.hasArt ?: false
        // Cache 60 min: imagem fresca é servida direto — MAS só quando já tem arte, porque a
        // arte pode ter sido gerada depois da última consulta (geração roda em background no
        // srv-mupa). Sem arte ainda, cai no fluxo abaixo mesmo com foto fresca: o passo 0 dali
        // faz um GET barato só pra checar se a arte já ficou pronta, sem rebaixar a foto de novo
        // (downloadProductImageIfNeeded já reaproveita o arquivo fresco internamente).
        if (existing != null && isProductImageFresh(normalizedEan) && cachedHasArt) {
            return PreloadedImage(existing, null, true)
        }
        if (!isOnline) return PreloadedImage(existing, null, cachedHasArt)

        suspend fun saveToCacheAndReturn(path: String, t: PriceTheme?, hasArt: Boolean): PreloadedImage {
            val cache = db.priceCacheDao().getByEan(normalizedEan)
            if (cache != null) {
                runCatching {
                    val cachedProd = parseProductFromCache(normalizedEan, cache)
                    if (cachedProd != null) {
                        val updatedProd = cachedProd.copy(image = path, theme = t ?: cachedProd.theme, hasArt = hasArt)
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
            return PreloadedImage(path, t, hasArt)
        }

        // 0. Arte publicitária gerada por IA (produtos-imgs/Gemini, srv-mupa). Quando existe,
        // tem prioridade sobre qualquer outra fonte de imagem — é a peça de campanha completa,
        // com espaço reservado para o badge de preço ser sobreposto (ver PlayerActivity).
        // É uma funcionalidade exclusiva da Mupa, então sempre consulta o endpoint fixo,
        // independente de haver um step "lookup_image" customizado por integração — EXCETO pra
        // UnidaSul (pedido do usuário, 2026-11-13): a rede de algumas lojas bloqueia/redireciona
        // o srv-mupa sem nunca responder, atrasando ~4s TODO scan só pra nunca achar arte mesmo
        // (produto de terceiro, Mupa nunca gerou arte pra ele). Pula direto pro passo 1 (foto da
        // própria API da UnidaSul, via ImagemUrl/clientImageUrl).
        //
        // Orientação do terminal (ver isDeviceVertical): o produtos-imgs mantém duas artes
        // independentes por produto (horizontal <ean>.webp / vertical <ean>_vertical.webp) —
        // pedimos só a que combina com o terminal físico deste aparelho, nunca as duas.
        val orientacao = if (isDeviceVertical()) "vertical" else "horizontal"
        val skipMupaImageSearch = config.integration == "integra-unidasul"
        // "Modo Publicidade das Imagens" (pedido do usuário, 2026-11-17): flag por dispositivo,
        // só pra este passo 0 (arte de IA) — diferente de skipMupaImageSearch acima, que também
        // pula o passo 3 (e é específico da UnidaSul, por motivo de rede, não de preferência).
        val adArtworkEnabled = runCatching { SettingsManager(context).getAdArtworkEnabled() }.getOrDefault(true)
        var meta: ProductImageMeta? = null
        val step3 = if (skipMupaImageSearch) null else config.steps.firstOrNull { it.type == "lookup_image" }
        if (!skipMupaImageSearch && adArtworkEnabled) {
            meta = fetchProductImageMeta(ean = normalizedEan, step = step3, orientacao = orientacao)
            if (meta?.arteUrl.isNullOrBlank() && step3 != null) {
                meta = fetchProductImageMeta(ean = normalizedEan, step = null, orientacao = orientacao)
            }
        }
        val arteUrl = meta?.arteUrl
        if (!arteUrl.isNullOrBlank()) {
            // Chave de cache própria por orientação ("_arte"/"_arte_vertical"): a foto crua e a
            // arte do mesmo EAN não podem dividir o arquivo "$ean.webp" — se a crua já tiver
            // sido baixada e estiver fresca (<60min), downloadProductImageIfNeeded reaproveitaria
            // o arquivo errado (a crua) achando que já é a arte. Pela mesma razão, a arte
            // horizontal e a vertical do mesmo EAN também não podem dividir chave entre si —
            // um terminal vertical não pode acabar servindo a arte horizontal cacheada (ou
            // vice-versa) só porque os dois passaram pelo mesmo aparelho em algum teste.
            val chaveCacheArte = if (orientacao == "vertical") "${normalizedEan}_arte_vertical" else "${normalizedEan}_arte"
            // A arte vira o fundo da tela toda — usa o maior lado da tela do próprio aparelho
            // como teto de resolução, em vez do teto de 512px pensado pra thumbnail de produto,
            // senão fica visivelmente pixelizada ao esticar de volta pro tamanho da tela.
            val screenMaxDim = maxOf(
                context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels,
            )
            val arteLocal = runCatching {
                downloadProductImageIfNeeded(ean = chaveCacheArte, rawUrl = arteUrl, maxDim = screenMaxDim)
            }.getOrNull()
            if (arteLocal != null) {
                val arteTheme =
                    meta?.let {
                        PriceTheme(signature = it.signature, light = it.light, dark = it.dark)
                    }?.takeIf { it.signature != null || it.dark != null || it.light != null }
                return saveToCacheAndReturn(arteLocal, arteTheme, true)
            }
        }

        // 1. Prioritize client-provided remote image URL
        val clientImageUrl = cachedProduct?.clientImageUrl
        if (!clientImageUrl.isNullOrBlank() && clientImageUrl.startsWith("http")) {
            val clientLocal = runCatching { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = clientImageUrl) }.getOrNull()
            if (clientLocal != null) {
                // A foto veio direto da API própria do cliente (não da Mupa), então srv-mupa
                // ainda não tem essa imagem nem a arte publicitária dela — mesmo tratamento já
                // dado ao Komprão logo abaixo: dispara em background o upload + geração da arte
                // (o endpoint grava a foto crua também, se ainda não tiver), pra que a PRÓXIMA
                // consulta desse EAN (passo 0 acima) já encontre 'imagem_url_arte' pronta.
                requestArtGeneration(ean = normalizedEan, imagePath = clientLocal)
                return saveToCacheAndReturn(clientLocal, null, false)
            }
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
                if (kompraoLocal != null) {
                    // A foto veio da API própria do Komprão (não da Mupa), então srv-mupa ainda
                    // não tem essa imagem nem a arte publicitária dela. Dispara em background o
                    // upload + geração da arte, pra que a PRÓXIMA consulta (passo 0 acima) já
                    // encontre 'imagem_url_arte' pronta e mostre o layout novo com o preço.
                    requestArtGeneration(ean = normalizedEan, imagePath = kompraoLocal)
                    return saveToCacheAndReturn(kompraoLocal, null, false)
                }
            } else {
                Log.w("MPlayerPrice", "komprao_image_skip sku=$sku hasToken=${token.isNotBlank()}")
            }
        }

        // 3. Fallback to normal VTEX and Mupa image flow — pedido do usuário (2026-11-13):
        // pulado inteiro pra UnidaSul, mesma razão do passo 0 (busca de imagem na Mupa nunca
        // deve rodar pra esse cliente, só a foto que vem da própria API dele no passo 1 acima).
        var mupaLocal: String? = null
        if (!skipMupaImageSearch) {
            val vtexUrl = fetchVtexImageUrlFromApiProdutos(ean = normalizedEan)
            val vtexLocal = vtexUrl?.let { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = it) }
            if (vtexLocal != null) return saveToCacheAndReturn(vtexLocal, null, false)

            // 2ª opção garantida: imagem crua da API da Mupa (srv-mupa), reaproveitando o 'meta'
            // já buscado no passo 0 acima (evita uma segunda chamada de rede quando possível).
            mupaLocal = meta?.imageUrl?.let { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = it) }
            if (mupaLocal == null && step3 != null && meta?.imageUrl == null) {
                val fallbackMeta = fetchProductImageMeta(ean = normalizedEan, step = null)
                val fallbackLocal = fallbackMeta?.imageUrl?.let { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = it) }
                if (fallbackLocal != null) {
                    meta = fallbackMeta
                    mupaLocal = fallbackLocal
                }
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
        if (mupaLocal != null) return saveToCacheAndReturn(mupaLocal, theme, false)

        // Todas as fontes falharam: registra o EAN para um técnico resolver manualmente depois.
        // Fire-and-forget — não afeta o retorno nem a velocidade da consulta de preço.
        reportMissingImage(normalizedEan)
        return PreloadedImage(null, null, false)
    }

    /**
     * Busca só uma FOTO CRUA do produto (nunca arte publicitária) — passos 1-3 de
     * [resolveNormalPreloadedImage] (foto própria do cliente, API do Komprão, VTEX/Mupa),
     * sem o passo 0 (arte) que normalmente roda primeiro e pode retornar antes de chegar aqui.
     * Usado pela Consulta Patrocinada ([preloadProductImageAndTheme]) quando o fluxo normal já
     * achou/tem arte em cache para o EAN — a arte nunca serve de thumbnail, então essa foto
     * precisa ser buscada à parte.
     */
    private suspend fun fetchPlainProductPhoto(normalizedEan: String, config: PriceConfig): String? {
        val cachedProduct = db.priceCacheDao().getByEan(normalizedEan)?.let { cache ->
            runCatching { parseProductFromCache(normalizedEan, cache) }.getOrNull()
        }
        val skipMupaImageSearch = config.integration == "integra-unidasul"

        // 1. Foto própria do cliente (API dele, não a Mupa)
        val clientImageUrl = cachedProduct?.clientImageUrl
        if (!clientImageUrl.isNullOrBlank() && clientImageUrl.startsWith("http")) {
            val clientLocal = runCatching { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = clientImageUrl) }.getOrNull()
            if (clientLocal != null) {
                requestArtGeneration(ean = normalizedEan, imagePath = clientLocal)
                return clientLocal
            }
        }

        // 2. API de imagem do Komprão (OnWay), por SKU
        val isKomprao = config.integration.contains("komprao", ignoreCase = true) || config.integration.contains("komprão", ignoreCase = true)
        if (isKomprao) {
            val sku = cachedProduct?.id ?: normalizedEan
            val token = obterTokenKomprao()
            if (sku.isNotBlank() && token.isNotBlank()) {
                val kompraoUrl = "https://apionway.superkoch.com.br/on-way/product/image/$sku"
                val headers = mapOf("accept" to "image/jpeg", "Authorization" to "Bearer $token")
                val kompraoLocal = runCatching {
                    downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = kompraoUrl, headers = headers)
                }.getOrNull()
                if (kompraoLocal != null) {
                    requestArtGeneration(ean = normalizedEan, imagePath = kompraoLocal)
                    return kompraoLocal
                }
            }
        }

        // 3. Fallback VTEX / foto crua da Mupa (srv-mupa)
        if (!skipMupaImageSearch) {
            val vtexUrl = fetchVtexImageUrlFromApiProdutos(ean = normalizedEan)
            val vtexLocal = vtexUrl?.let { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = it) }
            if (vtexLocal != null) return vtexLocal

            val meta = fetchProductImageMeta(ean = normalizedEan, step = null)
            val mupaLocal = meta?.imageUrl?.let { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = it) }
            if (mupaLocal != null) return mupaLocal
        }
        return null
    }

    /**
     * Envia a foto crua já baixada (ex.: da API própria do Komprão) pro srv-mupa gerar e salvar
     * a arte publicitária do produto. Fire-and-forget em [analyticsScope]: não bloqueia nem
     * atrasa a consulta de preço em andamento — o resultado só é aproveitado na próxima consulta
     * desse EAN, quando o passo 0 de [preloadProductImageAndTheme] encontrar 'imagem_url_arte'.
     */
    private fun requestArtGeneration(ean: String, imagePath: String) {
        analyticsScope.launch {
            runCatching {
                val file = File(imagePath)
                if (!file.exists() || file.length() <= 0L) return@launch
                val mime =
                    when (file.extension.lowercase(Locale.ROOT)) {
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        else -> "image/webp"
                    }
                val baseUrl = SettingsManager(context).getImageServerBaseUrl()
                val orientacao = if (isDeviceVertical()) "vertical" else "horizontal"
                val url = "$baseUrl/produto-imagem/$ean/gerar-arte" + if (orientacao == "vertical") "?orientacao=vertical" else ""
                val req = Request.Builder()
                    .url(url)
                    .post(file.readBytes().toRequestBody(mime.toMediaType()))
                    .build()
                httpArtUpload.newCall(req).execute().use { resp ->
                    Log.i("MPlayerPrice", "art_generation_request ean=$ean status=${resp.code}")
                }
            }.onFailure {
                Log.w("MPlayerPrice", "art_generation_request_failed ean=$ean err=${it.message}")
            }
        }
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

    private suspend fun fetchProductImageMeta(ean: String, step: PriceStep?, orientacao: String = "horizontal"): ProductImageMeta? {
        return runCatching {
            fetchProductImageMetaInner(ean, step, orientacao)
        }.onFailure {
            Log.w("MPlayerScan", "fetch_image_meta_failed ean=$ean step=${step?.type} err=${it.javaClass.simpleName}:${it.message}")
        }.getOrNull()
    }

    private suspend fun fetchProductImageMetaInner(ean: String, step: PriceStep?, orientacao: String = "horizontal"): ProductImageMeta? {
        return run {
            val resp =
                if (step != null) {
                    // Steps de integração são específicos de cada cliente (API própria da loja) —
                    // não conhecem o conceito de orientação, que é exclusivo do produtos-imgs.
                    executeStep(step, JSONObject().put("ean", ean))
                } else {
                    val baseUrl = SettingsManager(context).getImageServerBaseUrl()
                    val url = "$baseUrl/produto-imagem/$ean" + if (orientacao == "vertical") "?orientacao=vertical" else ""
                    val req = Request.Builder()
                        .url(url)
                        .header("accept", "application/json")
                        .get()
                        .build()
                    httpImageMeta.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("product_image_http_${r.code}")
                        val text = r.body?.string().orEmpty()
                        JSONObject(text)
                    }
                }

            val imageUrl = normalizeExternalUrl(resp.optString("imagem_url", "").trim()).ifBlank { null }
            val arteUrl = normalizeExternalUrl(resp.optString("imagem_url_arte", "").trim()).ifBlank { null }
            val signature = resp.optString("cor_assinatura_produto", "").trim().ifBlank { null }
            val legibility = resp.optString("fundo_legibilidade", "").trim().ifBlank { null }
            val light = resp.optString("cor_dominante_claro", "").trim().ifBlank { null }
            val dark = resp.optString("cor_dominante_escuro", "").trim().ifBlank { null }
            ProductImageMeta(
                imageUrl = imageUrl,
                arteUrl = arteUrl,
                signature = signature,
                legibility = legibility,
                light = light,
                dark = dark,
            )
        }
    }

    /**
     * Telas Patrocinadas (Retail Media) — ver docs/SPEC_PATROCINIO_PRODUTOS_IMGS.md. Diferente da
     * arte por produto (step 0 de preloadProductImageAndTheme, 1 imagem por EAN), uma campanha
     * patrocinada é 1 imagem por EMPRESA/orientação que fica fixa enquanto QUALQUER produto é
     * consultado — por isso usa uma chave de cache própria (companyId, não EAN) e é checada só
     * 1x por janela de 60 min (mesmo TTL da imagem normal), nunca a cada scan.
     */
    private fun sponsorCacheKey(companyId: String, orientacao: String) = "sponsor_${companyId}_${orientacao}"

    private fun sponsorMetaFile(cacheKey: String): File = File(productsDir(), "$cacheKey.json")

    private fun saveSponsorMeta(cacheKey: String, campaign: SponsorCampaign) {
        runCatching {
            val json = JSONObject().apply {
                put("imageUrl", campaign.imageUrl)
                put("regionX0", campaign.region.x0)
                put("regionX1", campaign.region.x1)
                put("regionY0", campaign.region.y0)
                put("regionY1", campaign.region.y1)
                put("themeSignature", campaign.themeSignature)
                put("campaignId", campaign.campaignId)
                put("expiresAtEpochMs", campaign.expiresAtEpochMs)
            }
            sponsorMetaFile(cacheKey).writeText(json.toString())
        }
    }

    private fun loadSponsorMeta(cacheKey: String): SponsorCampaign? {
        return runCatching {
            val file = sponsorMetaFile(cacheKey)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            SponsorCampaign(
                imageUrl = json.optString("imageUrl"),
                region = SponsorRegion(
                    x0 = json.optDouble("regionX0", 0.0).toFloat(),
                    x1 = json.optDouble("regionX1", 1.0).toFloat(),
                    y0 = json.optDouble("regionY0", 0.0).toFloat(),
                    y1 = json.optDouble("regionY1", 1.0).toFloat(),
                ),
                themeSignature = json.optString("themeSignature").ifBlank { null },
                campaignId = json.optString("campaignId"),
                expiresAtEpochMs = if (json.has("expiresAtEpochMs") && !json.isNull("expiresAtEpochMs")) json.optLong("expiresAtEpochMs") else null,
            )
        }.getOrNull()
    }

    /**
     * Modo de teste local (sem depender do endpoint real /patrocinio-ativo, que ainda não existe
     * em nenhum backend): em build debug, se existir um arquivo
     * `sponsor_test.json` + `sponsor_test.webp|jpg|png` na pasta de produtos do app, usa esses
     * dados como se fosse a resposta do servidor. Só pra validar o pipeline A1/A2 em hardware
     * real antes do backend existir — nunca roda em build release.
     */
    private fun loadLocalTestSponsor(): SponsorCampaign? {
        if (!BuildConfig.DEBUG) return null
        return runCatching {
            val metaFile = File(productsDir(), "sponsor_test.json")
            if (!metaFile.exists()) return null
            val imageFile = productsDir().listFiles()
                ?.firstOrNull { it.isFile && it.nameWithoutExtension == "sponsor_test" && it.extension != "json" }
                ?: return null
            val json = JSONObject(metaFile.readText())
            SponsorCampaign(
                imageUrl = "file://${imageFile.absolutePath}",
                region = SponsorRegion(
                    x0 = json.optDouble("regionX0", 0.05).toFloat(),
                    x1 = json.optDouble("regionX1", 0.55).toFloat(),
                    y0 = json.optDouble("regionY0", 0.80).toFloat(),
                    y1 = json.optDouble("regionY1", 0.94).toFloat(),
                ),
                themeSignature = json.optString("themeSignature").ifBlank { null },
                campaignId = "teste_local",
                expiresAtEpochMs = null,
            )
        }.getOrNull()
    }

    private suspend fun fetchActiveSponsor(companyId: String, orientacao: String): SponsorCampaign? {
        return runCatching {
            val baseUrl = SettingsManager(context).getImageServerBaseUrl()
            val url = "$baseUrl/patrocinio-ativo?companyId=$companyId&orientacao=$orientacao"
            val req = Request.Builder().url(url).header("accept", "application/json").get().build()
            val json = httpImageMeta.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("sponsor_http_${r.code}")
                JSONObject(r.body?.string().orEmpty())
            }
            if (!json.optBoolean("ativo", false)) return null
            val regiao = json.optJSONObject("regiao") ?: return null
            SponsorCampaign(
                imageUrl = json.optString("imagemUrl", "").trim(),
                region = SponsorRegion(
                    x0 = regiao.optDouble("x0", 0.0).toFloat(),
                    x1 = regiao.optDouble("x1", 1.0).toFloat(),
                    y0 = regiao.optDouble("y0", 0.0).toFloat(),
                    y1 = regiao.optDouble("y1", 1.0).toFloat(),
                ),
                themeSignature = json.optString("corAssinatura", "").trim().ifBlank { null },
                campaignId = json.optString("campanhaId", "").trim(),
                // SimpleDateFormat, não java.time.Instant: minSdk 24 do flavor komprao não tem
                // desugaring habilitado, e java.time só existe nativo a partir do 26.
                expiresAtEpochMs = json.optString("expiraEm", "").trim().ifBlank { null }?.let { raw ->
                    runCatching {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .parse(raw)?.time
                    }.getOrNull()
                },
            )
        }.onFailure {
            Log.w("MPlayerScan", "fetch_active_sponsor_failed companyId=$companyId err=${it.javaClass.simpleName}:${it.message}")
        }.getOrNull()
    }

    /**
     * Resolve a campanha patrocinada ativa para este dispositivo (se houver), baixando e
     * cacheando a imagem localmente (mesma função de sempre, `downloadProductImageIfNeeded`, só
     * com uma chave de cache por empresa em vez de por EAN — reaproveita o TTL de 60 min, o
     * redimensionamento e a lógica de reuso de arquivo vencido sem duplicar nada). Só bate rede
     * quando o cache local não existe ou venceu; dentro da janela, serve do disco.
     */
    private suspend fun resolveActiveSponsor(orientacao: String): Pair<String, SponsorCampaign>? {
        val testSponsor = loadLocalTestSponsor()
        if (testSponsor != null) {
            return testSponsor.imageUrl.removePrefix("file://") to testSponsor
        }

        val companyId = resolveCompanyId()?.trim().orEmpty()
        if (companyId.isBlank()) return null
        val cacheKey = sponsorCacheKey(companyId, orientacao)

        val cachedMeta = loadSponsorMeta(cacheKey)
        val cachedImage = localProductImageFile(cacheKey)
        val now = System.currentTimeMillis()
        val cacheStillFresh = cachedMeta != null && cachedImage != null && isProductImageFresh(cacheKey) &&
            (cachedMeta.expiresAtEpochMs == null || cachedMeta.expiresAtEpochMs > now)
        if (cacheStillFresh) {
            return cachedImage!!.absolutePath to cachedMeta!!
        }

        val fresh = fetchActiveSponsor(companyId, orientacao)
        if (fresh == null || fresh.imageUrl.isBlank()) {
            // Sem campanha ativa (ou falha de rede): remove cache local pra não ficar servindo
            // uma campanha encerrada indefinidamente quando o servidor confirma que acabou.
            runCatching { localProductImageFile(cacheKey)?.delete() }
            runCatching { sponsorMetaFile(cacheKey).delete() }
            return null
        }
        val screenMaxDim = maxOf(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
        )
        val localPath = runCatching {
            downloadProductImageIfNeeded(ean = cacheKey, rawUrl = fresh.imageUrl, maxDim = screenMaxDim)
        }.getOrNull() ?: return null
        saveSponsorMeta(cacheKey, fresh)
        return localPath to fresh
    }

    private fun sponsoredLookupCacheKey(id: String) = "sponsored_lookup_$id"

    /** Chamado a cada manifesto novo (fetch inicial ou refresh em segundo plano) — ver
     * PlayerActivity. Só troca a lista em memória; o download/cache acontece em
     * [precacheSponsoredLookups], chamado à parte (não bloqueia o parse do manifesto). */
    fun updateSponsoredLookups(items: List<SponsoredLookupItem>) {
        sponsoredLookups = items
    }

    /**
     * Pré-baixa TODAS as peças da lista atual (não só a que vai ser mostrada na próxima consulta)
     * — contrato exige exibição instantânea no momento do scan, sem latência de rede
     * (docs/2026-11-23-mplayer-android-sponsored-lookup.md, §4.1). Cada peça é independente
     * (`runCatching` por item): uma falha não derruba as outras.
     */
    suspend fun precacheSponsoredLookups() = withContext(Dispatchers.IO) {
        val screenMaxDim = maxOf(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
        )
        for (item in sponsoredLookups) {
            runCatching {
                downloadProductImageIfNeeded(
                    ean = sponsoredLookupCacheKey(item.id),
                    rawUrl = item.url,
                    maxDim = screenMaxDim,
                )
            }
        }
    }

    /** Round-robin: cada chamada avança pra próxima peça da lista atual, ciclando. `null` se a
     * lista estiver vazia (sem Tag patrocinada pra este dispositivo) ou se a peça escolhida ainda
     * não tiver sido baixada (ex. logo após o pareamento, antes do primeiro
     * [precacheSponsoredLookups] terminar) — nesse caso baixa na hora, pra não perder o scan. */
    private suspend fun resolveActiveSponsoredLookup(): String? {
        val items = sponsoredLookups
        if (items.isEmpty()) return null
        val idx = sponsoredLookupIndex.getAndUpdate { (it + 1) % items.size }.mod(items.size)
        val item = items[idx]
        val cacheKey = sponsoredLookupCacheKey(item.id)
        localProductImageFile(cacheKey)?.let { return it.absolutePath }
        val screenMaxDim = maxOf(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
        )
        return runCatching {
            downloadProductImageIfNeeded(ean = cacheKey, rawUrl = item.url, maxDim = screenMaxDim)
        }.getOrNull()
    }

    /**
     * true quando o terminal físico está montado em pé (retrato) — decide se o app pede a arte
     * publicitária horizontal ou vertical (ver preloadProductImageAndTheme, passo 0). Terminais
     * de consulta de preço ficam fixos numa orientação (aparafusados no suporte da loja), então
     * basta olhar a proporção real da tela (`displayMetrics`) em vez de reagir a rotação de
     * sensor — não existe rotação em tempo real nesse tipo de aparelho.
     */
    private fun isDeviceVertical(): Boolean {
        val metrics = context.resources.displayMetrics
        return metrics.heightPixels > metrics.widthPixels
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
        headers: Map<String, String>? = null,
        // Fotos de produto (pequenas, mostradas ao lado de texto) não precisam de mais que isso.
        // A arte publicitária é passada à parte com um valor bem maior — ela vira o FUNDO DA TELA
        // TODA, então rebaixar pra 512px (pensado pra thumbnail) deixava a arte visivelmente
        // pixelizada quando esticada de volta pro tamanho da tela.
        maxDim: Int = 512,
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
                    val fromValue = s.optDouble("fromValue", Double.NaN).takeIf { !it.isNaN() }
                    val currencySymbol = s.optString("currencySymbol", "").ifBlank { "R$" }
                    list += ProductPriceSlot(label = label, value = value, field = field, isPromo = isPromo, isClub = isClub, fromValue = fromValue, currencySymbol = currencySymbol)
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
                hasArt = o.optBoolean("hasArt", false),
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
                    // Bug real (2026-11-17): faltavam aqui — um produto recarregado do cache
                    // (ver query(), fromCache) perdia o símbolo de moeda (voltava pro "R$" padrão
                    // de ProductPriceSlot em QUALQUER slot, inclusive Dólar/Peso) e o "De: X"
                    // riscado, mesmo com os dois presentes na consulta original.
                    .put("fromValue", s.fromValue)
                    .put("currencySymbol", s.currencySymbol)
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
            .put("hasArt", product.hasArt)
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
            Log.d("MPlayerPrice", "komprao_price_resp ean=$ean status=${resp.code} body=${body.take(2000)}")
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
    // integra-tcserver  (Gertec TCServer — servidor de consulta de preço
    // local da loja)
    //
    // Achado real (2026-10-21, testado localmente com o .jar oficial da
    // Gertec): o TCServer fala, em teoria, um protocolo TCP binário na
    // porta 6500 para terminais físicos Gertec (handshake "#ok" →
    // identificação do terminal → "#alwayslive"/"#alwayslive_ok" →
    // "#<ean>" → "#<DESCRIÇÃO>|<PREÇO>" ou "#nfound") — mas essa build
    // trava o listener INTEIRO (para de aceitar qualquer conexão nova,
    // de qualquer terminal) ao receber uma identificação que não
    // reconhece, sem logar exceção nenhuma. Risco demais pra produção
    // (um app novo poderia derrubar o atendimento de todos os terminais
    // físicos da loja).
    //
    // Em vez disso, usamos o endpoint HTTP que o próprio TCServer já
    // expõe (embedded Jetty): GET /barcode?param=<ean>. É o mesmo
    // endpoint que a página de consulta web do TCServer usa (ver
    // webroot/index.jsp no .tc-server da instalação) e pelo qual o
    // TCServer encaminha a consulta ao backend de preços configurado
    // (tcxml/price_checker.xml) — ponto de integração real e estável,
    // testado sem travar nada, não um atalho. Retorna HTML
    // (webroot/product.jsp ou webroot/notfound.jsp), não JSON.
    //
    // Endereço configurado em Configurações (SettingsManager
    // tc_server_address, formato "ip:porta"; porta = a do servidor HTTP
    // do TCServer, tipicamente 8080 — não a 6500 do protocolo TCP).
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

        val address = SettingsManager(context).getTcServerAddress()
        if (address.isBlank()) {
            Log.w("MPlayerPrice", "tcserver_address_not_configured ean=$ean")
            return null
        }
        // address = "ip:porta" (ex: 192.168.1.175:8080)
        val baseAddr = address.removePrefix("http://").removePrefix("https://").trimEnd('/')

        val startedAt = System.currentTimeMillis()
        var product: PriceProduct? = null
        try {
            val html = fetchTcServerBarcodeHtml(baseAddr, ean)
            Log.d("MPlayerPrice", "tcserver_resp ean=$ean resp=${html?.take(300)}")
            product = html?.let { parseTcServerHtml(ean, it) }?.let { attachLocalImageIfExists(it) }
            product = product?.let { appendForeignCurrencySlots(baseAddr, it) }

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

    /**
     * GET http://{addr}/barcode?param={ean} — retorna o HTML do TCServer
     * (webroot/product.jsp quando encontrado, webroot/notfound.jsp quando não).
     */
    private fun fetchTcServerBarcodeHtml(addr: String, ean: String): String? {
        val url = "http://$addr/barcode?param=$ean"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.d("MPlayerPrice", "tcserver_http_status=${resp.code} ean=$ean")
                return null
            }
            return resp.body?.string()
        }
    }

    // Formato real de webroot/product.jsp (testado ao vivo contra o .jar da Gertec):
    //   Codigo de barras: <ean>
    //   Descricao: <descrição>
    //   Label1: <rótulo1>
    //   Preco1: <preço1>
    //   Label2: <rótulo2>        (opcional)
    //   Preco2: <preço2>         (opcional)
    // webroot/notfound.jsp: 'O produto "<ean>" nao foi encontrado'
    private fun parseTcServerHtml(ean: String, html: String): PriceProduct? {
        if (html.contains("nao foi encontrado")) return null

        fun field(name: String): String? =
            Regex("$name:\\s*([^<]*)").find(html)?.groupValues?.get(1)?.trim()?.ifBlank { null }

        val description = field("Descricao") ?: return null
        val preco1 = field("Preco1")?.replace(",", ".")?.toDoubleOrNull() ?: return null
        val label1 = field("Label1")
        val preco2 = field("Preco2")?.replace(",", ".")?.toDoubleOrNull()
        val precoPromo = preco2?.takeIf { it < preco1 }

        val slots = ArrayList<ProductPriceSlot>()
        slots += ProductPriceSlot(
            label = label1?.uppercase()?.ifBlank { null } ?: "PREÇO NORMAL",
            value = preco1,
            field = "price",
            isPromo = false,
            isClub = false,
        )
        if (precoPromo != null) {
            // Label2 do TCServer é config de loja (ex: "Custom price", LABEL2 em
            // config.properties) — não é um texto de marketing pro cliente final, por isso
            // ignorado aqui a favor de um rótulo fixo "EM OFERTA" (achado real, 2026-10-21,
            // EAN 7891010980061: Label2="Custom price", Preco2=19,32 < Preco1=22,49).
            slots += ProductPriceSlot(
                label = "EM OFERTA",
                value = precoPromo,
                field = "price_wholesale",
                isPromo = true,
                isClub = false,
                fromValue = preco1,  // "de" riscado dentro do box de promoção
            )
        }

        return PriceProduct(
            id = null,
            ean = ean,
            description = description,
            price = preco1,
            originalPrice = null,
            clubPrice = null,
            priceClub = null,
            priceWholesale = precoPromo,
            pricePromotional = precoPromo,
            priceFrom = null,
            priceWeighable = null,
            cardPrice = null,
            stock = null,
            image = null,
            clientImageUrl = null,
            offer = null,
            packs = emptyList(),
            theme = null,
            offline = false,
            priceSlots = slots,
            xmlLayoutType = "multi_price",
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Conversão de moeda estrangeira (Dólar/Peso) via TCServer
    //
    // Duas linhas especiais cadastradas pelo cliente na MESMA tabela de produtos do TCServer
    // (PRICETAB.txt), com barcode literal "moeda_dolar"/"moeda_peso" — ex:
    //   moeda_dolar|Cotação Dólar|5,34|
    //   moeda_peso|Cotação Peso|0,0061|
    // "Preco1" é a cotação: quantos reais vale 1 unidade daquela moeda. Convertido aqui como
    // preco_em_reais / cotacao. Reaproveita o MESMO endpoint /barcode?param= já usado pra
    // produtos normais, sem cache — mesmo motivo do resto do TCServer (comentário em
    // queryTcServer): é local/rápido, e cachear aqui só atrasava o cliente ver uma cotação nova
    // (achado real, 2026-10-21: usuário trocou a cotação na PRICETAB.txt e a conversão não
    // mudou — tinha um cache de 10min aqui que não existe em nenhum outro lugar da integração).
    // ─────────────────────────────────────────────────────────────

    private suspend fun appendForeignCurrencySlots(baseAddr: String, product: PriceProduct): PriceProduct {
        val price = product.price ?: return product
        val settings = SettingsManager(context)
        if (!settings.getForeignCurrencyEnabled()) return product

        // Produto em oferta (Preco2 < Preco1, ver parseTcServerHtml): converte o preço de
        // OFERTA, não o preço cheio — é o que o cliente realmente vai pagar.
        val activePrice = product.priceSlots?.firstOrNull { it.isPromo }?.value ?: price

        val extraSlots = ArrayList<ProductPriceSlot>()
        if (settings.getShowUsdCurrency()) {
            fetchCurrencyQuote(baseAddr, "moeda_dolar")?.let { rate ->
                extraSlots += ProductPriceSlot(
                    label = "DÓLAR",
                    value = activePrice / rate,
                    field = "price_usd",
                    isPromo = false,
                    isClub = false,
                    currencySymbol = "US$",
                )
            }
        }
        if (settings.getShowArsCurrency()) {
            fetchCurrencyQuote(baseAddr, "moeda_peso")?.let { rate ->
                extraSlots += ProductPriceSlot(
                    label = "PESO",
                    value = activePrice / rate,
                    field = "price_ars",
                    isPromo = false,
                    isClub = false,
                    currencySymbol = "$",
                )
            }
        }
        if (extraSlots.isEmpty()) return product

        val baseSlots = product.priceSlots ?: listOf(
            ProductPriceSlot(label = "PREÇO NORMAL", value = price, field = "price", isPromo = false, isClub = false),
        )
        return product.copy(priceSlots = baseSlots + extraSlots)
    }

    /** Busca a cotação de uma moeda ("moeda_dolar"/"moeda_peso") no TCServer. Sem cache. */
    private suspend fun fetchCurrencyQuote(baseAddr: String, code: String): Double? {
        val html = runCatching { fetchTcServerBarcodeHtml(baseAddr, code) }.getOrNull()
        if (html == null || html.contains("nao foi encontrado")) {
            Log.w("MPlayerPrice", "currency_quote_not_found code=$code addr=$baseAddr")
            return null
        }
        val rate = Regex("Preco1:\\s*([^<]*)").find(html)?.groupValues?.get(1)?.trim()
            ?.replace(",", ".")?.toDoubleOrNull()?.takeIf { it > 0.0 }
        if (rate == null) {
            Log.w("MPlayerPrice", "currency_quote_parse_failed code=$code addr=$baseAddr")
            return null
        }
        return rate
    }

    // ─────────────────────────────────────────────────────────────
    // integra-unidasul  (terminal de consulta UnidaSul Distribuidora
    // Alimentícia, bandeiras Macromix/Rissul/etc.) — API oficial
    // "UnidaSul.Api.TerminalConsulta" (2026-11-11, pedido do usuário pra
    // ajustar o flavor de consulta pra essa API), substituindo o bridge
    // local raspado (/get_price) usado até então (ver histórico no fim
    // de docs/FEATURE_INTEGRACAO_UNIDASUL.md). Mesmo padrão de servidor
    // configurável do queryTcServer acima: host configurado em
    // Configurações (SettingsManager unidasul_server_address), blank =
    // integração desativada.
    //
    // GET http://f{filial}srvhml01.filial.unidasul.local/UnidaSul.Api.TerminalConsulta/api/ObterDadosPrecoProduto/{ean}
    //
    // Confirmado ao vivo (2026-11-11) contra o Swagger de Homolog
    // (http://f001srvhml01.filial.unidasul.local/UnidaSul.Api.TerminalConsulta/swagger):
    // - Sucesso (HTTP 200): {"Descricao":"...", "Preco":"R$ 4,29", "Condicoes":[],
    //   "Codigo":"Código: 2365121 - 7896423456561", "ImagemUrl":"https://..."}.
    // - Não encontrado: HTTP 404 de verdade com {"mensagem":"Produto não encontrado."}
    //   — diferente do bridge antigo, que sempre respondia 200 com "error" no corpo.
    // - Swagger declara Bearer/JWT, mas respondeu sem token nenhum em Homolog;
    //   segundo o usuário, em produção também não exige (só funciona dentro da
    //   rede da loja).
    // - 2026-11-11 assumia que Produção usava um host/virtual-directory diferentes de Homolog
    //   (detectado pelo "hml" no host). 2026-11-13, em campo (device ST103ANLFKBC092, filial
    //   074): o host "de produção" sem "hml" respondeu HTTP 503 (serviço fora do ar na loja,
    //   não é problema do app), e o ÚNICO caminho confirmado funcionando de verdade (Swagger +
    //   navegador do aparelho) foi sempre "f{filial}srvhml01..." +
    //   "UnidaSul.Api.TerminalConsulta" — por isso a consulta agora só extrai os 3 dígitos da
    //   filial do host configurado (ver unidasulBuildUrl) e monta a URL sempre com esse padrão
    //   fixo, em vez de tentar adivinhar o ambiente pelo "hml" no host.
    //
    // Campo "Condicoes" (oferta por quantidade) é parseado como 2º price slot — ver
    // parseUnidasulProdutoJson e PriceQueryEngineUnidasulTest para os 3 formatos reais
    // confirmados em ~230 EANs de campo (2026-11-11).
    // ─────────────────────────────────────────────────────────────

    private suspend fun queryUnidasul(ean: String, config: PriceConfig, filial: String): PriceProduct? {
        val now = System.currentTimeMillis()
        val cache = db.priceCacheDao().getByEan(ean)
        val fromCache = cache != null && (now - cache.updatedAtEpochMs) <= config.cacheMinutes * 60 * 1000L

        if (fromCache) {
            val product = normalizeCachedProduct(parseProductFromCache(ean, cache))
            insertEvent(ean = ean, filial = filial, responseTimeMs = 0L, fromCache = true, success = product != null)
            return product
        }

        val address = SettingsManager(context).getUnidasulServerAddress()
        if (address.isBlank()) {
            Log.w("MPlayerPrice", "unidasul_address_not_configured ean=$ean")
            return null
        }
        val host = address.removePrefix("http://").removePrefix("https://").trimEnd('/')

        val startedAt = System.currentTimeMillis()
        var product: PriceProduct? = null
        try {
            val json = fetchUnidasulPrice(host, ean)
            Log.d("MPlayerPrice", "unidasul_resp ean=$ean resp=${json?.toString()?.take(600)}")
            product = json?.let { parseUnidasulResponse(ean, it) }?.let { attachLocalImageIfExists(it) }

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
            Log.w("MPlayerPrice", "query_unidasul_failed ean=$ean host=$host err=${t.javaClass.simpleName}:${t.message}")
            // Fallback para cache mesmo expirado quando o servidor da filial está inacessível.
            if (cache != null) {
                product = normalizeCachedProduct(parseProductFromCache(ean, cache))?.copy(offline = true)
            }
        } finally {
            val took = System.currentTimeMillis() - startedAt
            insertEvent(ean = ean, filial = filial, responseTimeMs = took, fromCache = false, success = product != null)
        }
        return product
    }

    private fun fetchUnidasulPrice(host: String, ean: String): JSONObject? {
        val url = unidasulBuildUrl(host, "api/ObterDadosPrecoProduto/$ean")
        if (url == null) {
            Log.w("MPlayerPrice", "unidasul_filial_code_not_found host=$host ean=$ean")
            return null
        }
        val req = Request.Builder().url(url).header("accept", "application/json").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.d("MPlayerPrice", "unidasul_price_resp ean=$ean status=${resp.code} body=${body.take(600)}")
            // "Produto não encontrado" chega com HTTP 404 de verdade (API oficial, diferente do
            // bridge antigo que sempre respondia 200) — qualquer status não-2xx é tratado como
            // "sem produto" aqui; a decisão de cair pro cache fica pro catch acima (erro de rede).
            if (!resp.isSuccessful) return null
            return runCatching { JSONObject(body) }.getOrNull()
        }
    }

    // Fina casca com acesso a Context (cache de imagem local) em volta da função pura
    // abaixo — mantém a lógica de parsing de verdade 100% testável sem Android.
    private fun parseUnidasulResponse(ean: String, json: JSONObject): PriceProduct? {
        return parseUnidasulProdutoJson(ean, json)
    }
}

// "A partir de N Un:" seguido de "R$ Y,YY cada" — mesmo padrão de oferta por quantidade já
// existia no bridge antigo (lblPrecoDC/lblPrecoDC2), só que ali vinha em 2 campos fixos; aqui
// vem como 2 strings consecutivas dentro de "Condicoes" (ver parseUnidasulProdutoJson).
private val UNIDASUL_QTY_LABEL_REGEX = Regex("""A partir de\s+(\d+)\s*Un""", RegexOption.IGNORE_CASE)
private val UNIDASUL_QTY_PRICE_REGEX = Regex("""R\$\s*([0-9.,]+)\s*cada""", RegexOption.IGNORE_CASE)

// Achado real em campo (2026-11-13, device ST103ANLFKBC092, filial 074): o host de "produção"
// sem "hml" (ex. "f074srv01.filial.unidasul.local") respondeu 503 (serviço fora do ar na IIS da
// loja) — problema do lado da UnidaSul, não do app. O único caminho confirmado funcionando de
// verdade (testado contra o Swagger e direto no navegador do aparelho) é sempre
// "f{3 dígitos}srvhml01.filial.unidasul.local" com o virtual directory
// "UnidaSul.Api.TerminalConsulta" — por isso a consulta não depende mais do host inteiro
// digitado nas Configurações, só extrai os 3 dígitos da filial dele e monta a URL sempre com
// esse mesmo padrão fixo. Função de topo (não um método privado da engine) pra dar pra reusar em
// SettingsActivity (botão "Testar Conexão" precisa do mesmo cálculo, sem precisar instanciar
// PriceQueryEngine).
private val UNIDASUL_FILIAL_CODE_REGEX = Regex("""f(\d{3})""", RegexOption.IGNORE_CASE)

internal fun unidasulFilialCode(host: String): String? =
    UNIDASUL_FILIAL_CODE_REGEX.find(host)?.groupValues?.get(1)

internal fun unidasulBuildUrl(host: String, pathSuffix: String): String? {
    val filial = unidasulFilialCode(host) ?: return null
    return "http://f${filial}srvhml01.filial.unidasul.local/UnidaSul.Api.TerminalConsulta/$pathSuffix"
}

// Função pura (sem Context/AppDatabase) com a lógica de parsing de verdade da resposta da
// API oficial UnidaSul.Api.TerminalConsulta — extraída fora da classe de propósito pra dar
// pra testar direto com respostas REAIS capturadas contra o Swagger de Homolog em 2026-11-11
// (ver PriceQueryEngineUnidasulTest.kt), sem precisar instanciar PriceQueryEngine nem mockar
// Android. `image` sempre sai null aqui (cache de imagem local é resolvido por
// attachLocalImageIfExists, chamado pelo caller que tem Context).
internal fun parseUnidasulProdutoJson(ean: String, json: JSONObject): PriceProduct? {
    val precoRaw = json.optString("Preco", "").trim()
    if (precoRaw.isBlank()) return null
    val preco = precoRaw
        .replace("R$", "")
        .trim()
        .replace(".", "")
        .replace(",", ".")
        .toDoubleOrNull()
        ?.takeIf { it > 0.0 } ?: return null

    val description = json.optString("Descricao", "").trim().ifBlank { null }
    val imagemUrl = json.optString("ImagemUrl", "").trim().ifBlank { null }

    // "Condicoes" — array de objetos de 1 campo cada, "Condicao_1"/"Condicao_2"/... → texto
    // livre. Confirmado em campo (2026-11-11, ~230 EANs reais) 3 formatos distintos:
    // 1. Oferta por quantidade: "A partir de N Un:" + "R$ Y,YY cada" + (opcional) "Na compra de
    //    N ou mais unidades, você economiza: ...". Os 2 primeiros virão em sequência — é o único
    //    formato que entra como 2º price slot (mesmo tratamento do bridge antigo).
    // 2. Preço normal repetido: "********** R$ X,XX por Unidade **********" — sempre igual ao
    //    `Preco` já mostrado, não é uma variação real. Ignorado de propósito.
    // 3. Promoção de combo/cross-sell em texto livre ("..::: ATENÇÃO: Promoção especial...",
    //    "NA COMPRA DE X LEVE GRÁTIS Y") — não dá pra extrair um preço com segurança disso,
    //    então também é ignorado por ora (não derruba o parse, só não gera slot).
    val condicoesTexts = ArrayList<String>()
    val condicoesArray = json.optJSONArray("Condicoes")
    if (condicoesArray != null) {
        for (i in 0 until condicoesArray.length()) {
            val obj = condicoesArray.optJSONObject(i) ?: continue
            val key = obj.keys().asSequence().firstOrNull() ?: continue
            val text = obj.optString(key, "").trim()
            if (text.isNotBlank()) condicoesTexts += text
        }
    }

    var precoQtd: Double? = null
    var precoQtdLabel: String? = null
    for (i in condicoesTexts.indices) {
        val labelMatch = UNIDASUL_QTY_LABEL_REGEX.find(condicoesTexts[i]) ?: continue
        val priceMatch = UNIDASUL_QTY_PRICE_REGEX.find(condicoesTexts.getOrNull(i + 1) ?: "") ?: continue
        val value = priceMatch.groupValues[1].replace(".", "").replace(",", ".").toDoubleOrNull()
            ?.takeIf { it > 0.0 && it < preco } ?: continue
        precoQtd = value
        precoQtdLabel = "A partir de ${labelMatch.groupValues[1]} Un"
        break
    }

    val slots = ArrayList<ProductPriceSlot>()
    slots += ProductPriceSlot(label = "PREÇO NORMAL", value = preco, field = "price", isPromo = false, isClub = false)
    if (precoQtd != null) {
        slots += ProductPriceSlot(
            label = precoQtdLabel ?: "OFERTA POR QUANTIDADE",
            value = precoQtd,
            field = "price_wholesale",
            isPromo = true,
            isClub = false,
            fromValue = preco,
        )
    }

    return PriceProduct(
        id = null,
        ean = ean,
        description = description,
        price = preco,
        originalPrice = null,
        clubPrice = null,
        priceClub = null,
        priceWholesale = precoQtd,
        pricePromotional = null,
        priceFrom = null,
        priceWeighable = null,
        cardPrice = null,
        stock = null,
        image = null,
        clientImageUrl = imagemUrl,
        offer = null,
        packs = emptyList(),
        theme = null,
        offline = false,
        priceSlots = slots,
        xmlLayoutType = "multi_price",
    )
}
