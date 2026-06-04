package com.mupa.player.enterprise.demo

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.palette.graphics.Palette
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

class DemoImageCache(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private companion object {
        const val ANDROID_ASSET_PREFIX = "file:///android_asset/"
    }

    fun imageFile(ean: String): File {
        val dir = File(context.cacheDir, "products")
        dir.mkdirs()
        return File(dir, "$ean.jpg")
    }

    fun colorsFile(ean: String): File {
        val dir = File(context.cacheDir, "metadata/colors")
        dir.mkdirs()
        return File(dir, "$ean.json")
    }

    fun readCachedColors(ean: String): DemoColors? {
        val f = colorsFile(ean)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            DemoColors(
                dominante = o.getString("dominante"),
                escuro = o.getString("escuro"),
                claro = o.getString("claro"),
                vibrante = o.getString("vibrante"),
                texto = o.getString("texto"),
            )
        }.getOrNull()
    }

    fun downloadImageIfNeeded(ean: String, url: String): File? {
        val file = imageFile(ean)
        if (file.exists() && file.length() > 0L) return file
        val normalized = url.trim()
        if (normalized.startsWith(ANDROID_ASSET_PREFIX)) {
            val assetPath = normalized.removePrefix(ANDROID_ASSET_PREFIX)
            file.outputStream().use { out ->
                context.assets.open(assetPath).use { input ->
                    input.copyTo(out)
                }
            }
            return file.takeIf { it.exists() && it.length() > 0L }
        }
        val req = Request.Builder().url(url).get().build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body ?: return null
        }
        file.outputStream().use { out ->
            body.byteStream().use { input ->
                input.copyTo(out)
            }
        }
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    fun extractAndCacheColors(ean: String, imageFile: File): DemoColors? {
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()

        var dominant = palette.dominantSwatch?.rgb
            ?: palette.vibrantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: Color.parseColor("#10141A")
        if (luminance(dominant) > 0.90) {
            dominant = palette.vibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: palette.darkMutedSwatch?.rgb
                ?: dominant
        }
        val dark = palette.darkVibrantSwatch?.rgb
            ?: palette.darkMutedSwatch?.rgb
            ?: darken(dominant, 0.55f)
        val light = palette.lightVibrantSwatch?.rgb
            ?: palette.lightMutedSwatch?.rgb
            ?: lighten(dominant, 0.72f)
        val vibrant = palette.vibrantSwatch?.rgb
            ?: palette.darkVibrantSwatch?.rgb
            ?: dominant

        val text = idealTextColor(dark)

        val colors =
            DemoColors(
                dominante = toHex(dominant),
                escuro = toHex(dark),
                claro = toHex(light),
                vibrante = toHex(vibrant),
                texto = toHex(text),
            )

        val o = JSONObject()
            .put("dominante", colors.dominante)
            .put("escuro", colors.escuro)
            .put("claro", colors.claro)
            .put("vibrante", colors.vibrante)
            .put("texto", colors.texto)

        colorsFile(ean).writeText(o.toString())
        return colors
    }

    private fun toHex(color: Int): String {
        return String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun idealTextColor(background: Int): Int {
        val luminance =
            (0.299 * Color.red(background) + 0.587 * Color.green(background) + 0.114 * Color.blue(background)) / 255.0
        return if (luminance < 0.55) Color.WHITE else Color.parseColor("#1A1A1A")
    }

    private fun luminance(color: Int): Double {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
    }

    private fun darken(color: Int, factor: Float): Int {
        return Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255),
        )
    }

    private fun lighten(color: Int, factor: Float): Int {
        fun c(v: Int): Int = (v + ((255 - v) * factor)).toInt().coerceIn(0, 255)
        return Color.rgb(c(Color.red(color)), c(Color.green(color)), c(Color.blue(color)))
    }
}
