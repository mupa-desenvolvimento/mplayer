package com.mupa.player.enterprise.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.mupa.player.enterprise.R

/**
 * Aplica a identidade tipográfica (Bebas Neue nos preços, Poppins Bold/Thin no nome do
 * produto) de forma centralizada, por uma lista curada de IDs conhecidos que os 12 layouts
 * `price_check_*.xml` + `item_price_slot.xml` compartilham por convenção. IDs que não
 * existirem no layout atualmente inflado simplesmente retornam null de `findViewById` e são
 * ignorados — uma única função cobre todas as variantes de layout sem duplicar código.
 *
 * Só troca o typeface, nunca a cor: cada seção já resolve sua própria cor de texto por
 * contraste (ex: `idealTextColor`), e isso continua intacto.
 */
object BrandTypography {

    private val BEBAS_NEUE_IDS = intArrayOf(
        R.id.priceCurrencyText,
        R.id.priceIntegerText,
        R.id.priceDecimalText,
        R.id.slotCurrency,
        R.id.slotIntegerPrice,
        R.id.slotDecimalPrice,
        R.id.normalPriceCurrency,
        R.id.normalPriceInteger,
        R.id.normalPriceDecimal,
        R.id.priceFromInteger,
        R.id.priceFromDecimal,
        R.id.priceInteger,
        R.id.priceDecimal,
        R.id.priceWholesaleInteger,
        R.id.priceWholesaleDecimal,
        R.id.priceClubInteger,
        R.id.priceClubDecimal,
        R.id.priceCardInteger,
        R.id.priceCardDecimal,
        R.id.priceSecondUnitValue,
    )

    private val POPPINS_BOLD_IDS = intArrayOf(
        R.id.priceNameText,
    )

    private val POPPINS_THIN_IDS = intArrayOf(
        R.id.priceNameSecondLineText,
        R.id.priceSmartCaption,
    )

    private var bebasNeue: Typeface? = null
    private var poppinsBold: Typeface? = null
    private var poppinsThin: Typeface? = null

    fun applyBrandTypography(root: View, context: Context) {
        val bebas = bebasNeue ?: runCatching {
            ResourcesCompat.getFont(context, R.font.bebas_neue_regular)
        }.getOrNull()?.also { bebasNeue = it }

        val bold = poppinsBold ?: runCatching {
            ResourcesCompat.getFont(context, R.font.poppins_bold)
        }.getOrNull()?.also { poppinsBold = it }

        val thin = poppinsThin ?: runCatching {
            ResourcesCompat.getFont(context, R.font.poppins_thin)
        }.getOrNull()?.also { poppinsThin = it }

        // Uma única varredura da árvore: item_price_slot.xml é inflado várias vezes (1 por
        // slot de preço) reaproveitando os mesmos IDs, então `findViewById` (que só acha a
        // 1ª ocorrência) deixaria os demais slots sem a fonte. Percorrer a árvore garante que
        // TODOS os preços — não só o primeiro — recebam Bebas Neue.
        applyRecursively(root) { view ->
            if (view !is TextView) return@applyRecursively
            when (view.id) {
                in BEBAS_NEUE_IDS -> bebas?.let { view.typeface = it }
                in POPPINS_BOLD_IDS -> bold?.let { view.typeface = it }
                in POPPINS_THIN_IDS -> thin?.let { view.typeface = it }
            }
        }
    }

    private fun applyRecursively(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRecursively(view.getChildAt(i), action)
            }
        }
    }
}
