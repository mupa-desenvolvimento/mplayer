package com.mupa.player.enterprise.price

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Valida parseUnidasulElementosJson contra respostas REAIS capturadas em produção no
 * App_TC_Unidasul.aia (comentários "Do It Result:" do depurador do App Inventor/Kodular,
 * 2025-04/09). Sem servidor real da UnidaSul disponível neste ambiente para teste ao vivo —
 * este teste é a validação de "nenhuma falha" nas regras de resposta (ver
 * docs/FEATURE_INTEGRACAO_UNIDASUL.md).
 */
class PriceQueryEngineUnidasulTest {

    private val hostPort = "192.168.0.10:5000"

    @Test
    fun `produto com oferta por quantidade vira 2 slots (amostra real CHOC SNICKERS)`() {
        val json = JSONObject(
            """
            {
              "elementos": {
                "lblDcrProduto": "CHOC SNICKERS MOUSSE MARACUJA 42G",
                "lblPreco": "3,79",
                "lblPrecoDC": "A partir de  3 Un:",
                "lblPrecoDC2": "3,33 cada",
                "lblScanner": "Na compra de  3 ou mais unidades, você economiza: R$ 0,46 por unidade"
              },
              "imagem_status": "indisponivel",
              "imagem_url": null,
              "success": true
            }
            """.trimIndent(),
        )

        val result = parseUnidasulElementosJson("7896423456561", json, hostPort)

        assertTrue(result != null)
        assertEquals("CHOC SNICKERS MOUSSE MARACUJA 42G", result!!.description)
        assertEquals(3.79, result.price!!, 0.001)
        assertEquals(3.33, result.priceWholesale!!, 0.001)
        assertNull(result.clientImageUrl)
        val slots = result.priceSlots!!
        assertEquals(2, slots.size)
        assertEquals("PREÇO NORMAL", slots[0].label)
        assertEquals("A partir de  3 Un", slots[1].label)
        assertEquals(3.33, slots[1].value, 0.001)
        assertEquals(3.79, slots[1].fromValue!!, 0.001)
        assertTrue(slots[1].isPromo)
    }

    @Test
    fun `produto simples sem oferta e com imagem relativa (amostra real FILTRO PAPEL)`() {
        val json = JSONObject(
            """
            {
              "ean": "7891021002127",
              "elementos": {
                "lblDcrProduto": "FILTRO PAPEL BRIGITTA 103 C/30 UN",
                "lblPreco": "4,70",
                "lblPrecoDC": "",
                "lblScanner": null
              },
              "elementos_nao_encontrados": ["scanner"],
              "filial": "035",
              "imagem_status": "disponivel",
              "imagem_url": "/imagem-produto/7891021002127",
              "success": true
            }
            """.trimIndent(),
        )

        val result = parseUnidasulElementosJson("7891021002127", json, hostPort)

        assertTrue(result != null)
        assertEquals(4.70, result!!.price!!, 0.001)
        assertEquals(1, result.priceSlots?.size)
        assertEquals("http://$hostPort/imagem-produto/7891021002127", result.clientImageUrl)
    }

    @Test
    fun `imagem_url absoluta passa direto sem prefixar host (amostra real LASANHA)`() {
        val json = JSONObject(
            """
            {
              "ean": "7894904201785",
              "elementos": {
                "lblDcrProduto": "LASANHA LEBON FGO 600G",
                "lblPreco": "14,99",
                "lblPrecoDC": "",
                "lblScanner": null
              },
              "filial": "035",
              "imagem_status": "disponivel",
              "imagem_url": "http://localhost:5000/imagem-produto/7894904201785",
              "success": true
            }
            """.trimIndent(),
        )

        val result = parseUnidasulElementosJson("7894904201785", json, hostPort)

        assertEquals("http://localhost:5000/imagem-produto/7894904201785", result!!.clientImageUrl)
    }

    @Test
    fun `imagem_status indisponivel nunca gera clientImageUrl mesmo com imagem_url presente`() {
        val json = JSONObject(
            """
            {
              "elementos": {
                "lblDcrProduto": "MASSA ORQUIDEA SEMOLA CONCHA 500G",
                "lblPreco": "3,99",
                "lblPrecoDC": "Cliente CLUBE RISSUL paga R$ 3,29"
              },
              "imagem_status": "indisponivel",
              "imagem_url": null,
              "success": true
            }
            """.trimIndent(),
        )

        val result = parseUnidasulElementosJson("9099005", json, hostPort)

        assertNull(result!!.clientImageUrl)
        // lblPrecoDC sem lblPrecoDC2 numérico ("Cliente CLUBE RISSUL paga...") não é o formato
        // "A partir de X Un:"/"Y cada" — regex não acha valor em lblPrecoDC2 inexistente, então
        // fica só 1 slot (preço normal), nunca quebra.
        assertEquals(1, result.priceSlots?.size)
    }

    @Test
    fun `erro de conexão com o servidor da filial retorna null (amostra real filial 086)`() {
        val json = JSONObject(
            """
            {
              "details": "HTTPConnectionPool(...)",
              "device_info": {"ip_address": "192.168.100.191"},
              "error": "Não foi possível conectar ao servidor da filial 086."
            }
            """.trimIndent(),
        )

        val result = parseUnidasulElementosJson("7891021002127", json, hostPort)

        assertNull(result)
    }

    @Test
    fun `produto nao encontrado (lblPreco literal null) retorna null`() {
        // Regra confirmada no .aia: o app original compara lblPreco (após troca de separador
        // decimal) contra a string literal "null" para decidir "Produto não encontrado ou
        // verifique o código de barras!" (ver Screen1.bky ~linha 21378).
        val json = JSONObject(
            """
            {
              "elementos": {
                "lblDcrProduto": null,
                "lblPreco": "null",
                "lblPrecoDC": ""
              },
              "success": true
            }
            """.trimIndent(),
        )

        val result = parseUnidasulElementosJson("0000000000000", json, hostPort)

        assertNull(result)
    }

    @Test
    fun `success false sem campo error tambem retorna null`() {
        val json = JSONObject("""{"success": false}""")

        val result = parseUnidasulElementosJson("123", json, hostPort)

        assertNull(result)
    }

    @Test
    fun `elementos ausente retorna null em vez de crashar`() {
        val json = JSONObject("""{"success": true}""")

        val result = parseUnidasulElementosJson("123", json, hostPort)

        assertNull(result)
    }

    @Test
    fun `lblPreco em branco retorna null`() {
        val json = JSONObject(
            """{"success": true, "elementos": {"lblPreco": ""}}""",
        )

        val result = parseUnidasulElementosJson("123", json, hostPort)

        assertNull(result)
    }

    @Test
    fun `lblPrecoDC2 sem numero valido nao gera 2o slot`() {
        val json = JSONObject(
            """
            {
              "success": true,
              "elementos": {
                "lblPreco": "10,00",
                "lblPrecoDC": "Alguma promoção",
                "lblPrecoDC2": "sem valor numerico aqui"
              }
            }
            """.trimIndent(),
        )

        val result = parseUnidasulElementosJson("123", json, hostPort)

        assertEquals(1, result!!.priceSlots?.size)
        assertNull(result.priceWholesale)
    }
}
