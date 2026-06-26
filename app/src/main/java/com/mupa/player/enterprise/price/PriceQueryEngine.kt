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
import com.mupa.player.enterprise.network.TlsCompat
import com.mupa.player.enterprise.storage.db.AppDatabase
import com.mupa.player.enterprise.storage.db.PriceCacheEntity
import com.mupa.player.enterprise.storage.db.PriceQueryEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class PriceQueryEngine(
    private val context: Context,
    private val deviceId: String,
) {
    private val db = AppDatabase.get(context)
    private val http = TlsCompat.newClient()
    private val sessionState = JSONObject()

    private suspend fun insertEvent(
        ean: String,
        filial: String,
        responseTimeMs: Long,
        fromCache: Boolean,
        success: Boolean
    ) = withContext(Dispatchers.IO) {
        runCatching {
            db.priceQueryEventDao().insert(
                PriceQueryEventEntity(
                    id = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    filial = filial,
                    ean = ean,
                    createdAtEpochMs = System.currentTimeMillis(),
                    responseTimeMs = responseTimeMs,
                    fromCache = fromCache,
                    success = success,
                    uploadedAtEpochMs = null,
                )
            )
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
        val filial = runCatching { DeviceCacheManager(context).load()?.filial }.getOrNull().orEmpty()

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
            )
        }

        return product
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
        if (existing != null) return@withContext existing to null
        if (!isOnline) return@withContext null to null

        val vtexUrl = fetchVtexImageUrlFromApiProdutos(ean = normalizedEan)
        val vtexLocal = vtexUrl?.let { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = it) }
        if (vtexLocal != null) return@withContext vtexLocal to null

        val step3 = config.steps.firstOrNull { it.type == "lookup_image" }
        val meta = fetchProductImageMeta(ean = normalizedEan, step = step3)
        val mupaLocal = meta?.imageUrl?.let { downloadProductImageIfNeeded(ean = normalizedEan, rawUrl = it) }
        val theme =
            meta?.let {
                PriceTheme(
                    signature = it.signature,
                    light = it.light,
                    dark = it.dark,
                )
            }?.takeIf { it.signature != null || it.dark != null || it.light != null }
        return@withContext mupaLocal to theme
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

    private fun normalizeCachedProduct(product: PriceProduct?): PriceProduct? {
        if (product == null) return null
        return product.copy(image = sanitizeLocalPathOrNull(product.image))
    }

    private fun downloadProductImageIfNeeded(ean: String, rawUrl: String): String? {
        val url = normalizeExternalUrl(rawUrl).trim()
        if (url.isBlank()) return null
        val optimizedTarget = File(productsDir(), "$ean.webp")
        if (optimizedTarget.exists() && optimizedTarget.length() > 0L) return optimizedTarget.absolutePath

        val existing =
            productsDir().listFiles()
                ?.firstOrNull { it.isFile && it.length() > 0L && it.nameWithoutExtension == ean }
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
        val req = Request.Builder().url(url).get().build()
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

        if (!tmpDownload.exists() || tmpDownload.length() == 0L) {
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
        return null
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
            id = state.optString("id", "").ifBlank { null },
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
}
