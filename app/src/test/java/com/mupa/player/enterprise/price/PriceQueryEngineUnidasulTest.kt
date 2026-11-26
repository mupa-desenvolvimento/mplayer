package com.mupa.player.enterprise.price

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Valida parseUnidasulProdutoJson contra respostas REAIS da API oficial
 * UnidaSul.Api.TerminalConsulta (confirmadas ao vivo em 2026-11-11 contra o Swagger de
 * Homolog, GET /api/ObterDadosPrecoProduto/{codigoBarras} — ver
 * docs/FEATURE_INTEGRACAO_UNIDASUL.md). Diferente da versão anterior (bridge local
 * /get_price, reverse-engenheirado do App_TC_Unidasul.aia): só temos 2 amostras reais
 * (sucesso e não-encontrado) — nenhum produto com "Condicoes" não-vazio foi capturado
 * ainda, então esse campo não é parseado por ora (documentado no comentário acima de
 * parseUnidasulProdutoJson).
 */
class PriceQueryEngineUnidasulTest {

    @Test
    fun `produto encontrado (amostra real CHOC SNICKERS, Homolog filial043)`() {
        val json = JSONObject(
            """
            {
              "Descricao": "CHOC SNICKERS MOUSSE MARAC ED LIMIT 42G",
              "Preco": "R$ 4,29",
              "Condicoes": [],
              "Codigo": "Código: 2365121 - 7896423456561",
              "ImagemUrl": "https://sabancoimagenspng.blob.core.windows.net/png1000x1000/7896423456561_1.png?sp=rl"
            }
            """.trimIndent(),
        )

        val result = parseUnidasulProdutoJson("7896423456561", json)

        assertTrue(result != null)
        assertEquals("CHOC SNICKERS MOUSSE MARAC ED LIMIT 42G", result!!.description)
        assertEquals(4.29, result.price!!, 0.001)
        assertEquals("https://sabancoimagenspng.blob.core.windows.net/png1000x1000/7896423456561_1.png?sp=rl", result.clientImageUrl)
        assertEquals(1, result.priceSlots?.size)
        assertEquals("PREÇO NORMAL", result.priceSlots!![0].label)
        assertNull(result.priceWholesale)
    }

    @Test
    fun `produto sem ImagemUrl nao crasha e fica sem imagem de cliente`() {
        val json = JSONObject(
            """
            {
              "Descricao": "PRODUTO QUALQUER",
              "Preco": "R$ 12,50",
              "Condicoes": [],
              "Codigo": "Código: 123 - 7891000000000"
            }
            """.trimIndent(),
        )

        val result = parseUnidasulProdutoJson("7891000000000", json)

        assertTrue(result != null)
        assertEquals(12.50, result!!.price!!, 0.001)
        assertNull(result.clientImageUrl)
    }

    @Test
    fun `preco em branco retorna null`() {
        val json = JSONObject("""{"Descricao": "X", "Preco": "", "Condicoes": []}""")

        val result = parseUnidasulProdutoJson("123", json)

        assertNull(result)
    }

    @Test
    fun `preco ausente retorna null`() {
        val json = JSONObject("""{"Descricao": "X", "Condicoes": []}""")

        val result = parseUnidasulProdutoJson("123", json)

        assertNull(result)
    }

    @Test
    fun `descricao ausente nao crasha, so fica null`() {
        val json = JSONObject("""{"Preco": "R$ 1,00", "Condicoes": []}""")

        val result = parseUnidasulProdutoJson("123", json)

        assertTrue(result != null)
        assertNull(result!!.description)
    }

    @Test
    fun `oferta por quantidade vira 2o slot (amostra real REFRESCO TANG)`() {
        val json = JSONObject(
            """
            {
              "Descricao": "REFRESCO PO TANG TANGER 18G",
              "Preco": "R$ 1,55",
              "Condicoes": [
                {"Condicao_1": "A partir de 6 Un:"},
                {"Condicao_2": "R$ 0,89 cada"},
                {"Condicao_3": "Na compra de 6 ou mais unidades, você economiza: R$ 0,66 por unidade"}
              ],
              "Codigo": "Código: 2361079 - 7622210571632",
              "ImagemUrl": "https://sabancoimagenspng.blob.core.windows.net/png1000x1000/7622210571632_1.png"
            }
            """.trimIndent(),
        )

        val result = parseUnidasulProdutoJson("7622210571632", json)

        assertTrue(result != null)
        assertEquals(1.55, result!!.price!!, 0.001)
        assertEquals(0.89, result.priceWholesale!!, 0.001)
        val slots = result.priceSlots!!
        assertEquals(2, slots.size)
        assertEquals("PREÇO NORMAL", slots[0].label)
        assertEquals("A partir de 6 Un", slots[1].label)
        assertEquals(0.89, slots[1].value, 0.001)
        assertEquals(1.55, slots[1].fromValue!!, 0.001)
        assertTrue(slots[1].isPromo)
    }

    @Test
    fun `promocao de combo em texto livre e ignorada, sem crashar (amostra real PEPSI BLACK)`() {
        val json = JSONObject(
            """
            {
              "Descricao": "REFRI PEPSI BLACK PET 2L",
              "Preco": "R$ 8,49",
              "Condicoes": [
                {"Condicao_1": "..::: ATENÇÃO: Promoção especial para este Produto :::.."},
                {"Condicao_2": "NA COMPRA DE 3 CARVAO IVOTI 3KG LEVE GRÁTIS 1 REFRI PEPSI PET 2L"}
              ],
              "Codigo": "Código: 9692084 - 7892840813444"
            }
            """.trimIndent(),
        )

        val result = parseUnidasulProdutoJson("7892840813444", json)

        assertTrue(result != null)
        assertEquals(8.49, result!!.price!!, 0.001)
        assertNull(result.priceWholesale)
        assertEquals(1, result.priceSlots?.size)
    }

    @Test
    fun `preco normal repetido em Condicoes nao gera 2o slot (amostra real AMACIANTE)`() {
        val json = JSONObject(
            """
            {
              "Descricao": "ACUCAR CRISTAL CARAVELAS COLOMBO 6X5KG",
              "Preco": "R$ 105,60",
              "Condicoes": [
                {"Condicao_1": "********** R$ 17,60 por Unidade **********"}
              ]
            }
            """.trimIndent(),
        )

        val result = parseUnidasulProdutoJson("7896894900112", json)

        assertTrue(result != null)
        assertNull(result!!.priceWholesale)
        assertEquals(1, result.priceSlots?.size)
    }

    @Test
    fun `mensagem de produto nao encontrado (amostra real HTTP 404) retorna null via ausencia de Preco`() {
        // A API retorna isso com HTTP 404 de verdade (ver fetchUnidasulPrice, que já descarta
        // qualquer resposta não-2xx antes de chegar aqui) — este teste cobre o shape do corpo
        // isoladamente, caso algum dia essa mensagem venha acompanhada de um 200 por engano.
        val json = JSONObject("""{"mensagem": "Produto não encontrado."}""")

        val result = parseUnidasulProdutoJson("0000000000000", json)

        assertNull(result)
    }
}
