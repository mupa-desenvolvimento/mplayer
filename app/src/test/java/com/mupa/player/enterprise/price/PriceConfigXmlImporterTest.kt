package com.mupa.player.enterprise.price

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Valida PriceConfigXmlImporter contra o "XML da consulta" real exportado pelo Mupa Studio
 * (queryToXml() em apps/web/src/bundle.ts) — amostra real usada em teste de bancada
 * (2026-11-25, integração On-Way/SuperKoch, loja 18).
 */
class PriceConfigXmlImporterTest {

    private val amostraReal = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mupa-studio-query version="1" integration="generic" timeout-ms="8000">
          <image enabled="true" kind="foto"/>
          <step type="authenticate" method="POST" url="https://apionway.superkoch.com.br/on-way/auth/login">
            <header name="Content-Type"><![CDATA[application/json]]></header>
            <header name="Accept"><![CDATA[application/json]]></header>
            <body><![CDATA[{"username":"${'$'}{studio_user}","password":"${'$'}{studio_pass}"}]]></body>
            <map field="access_token" path="data.token"/>
          </step>
          <step type="lookup_price" method="GET" url="https://apionway.superkoch.com.br/on-way/product/ean/${'$'}{ean}/store/18">
            <header name="Accept"><![CDATA[application/json]]></header>
            <header name="Authorization"><![CDATA[Bearer ${'$'}{access_token}]]></header>
            <map field="description" path="data.description"/>
            <map field="price" path="data.PRECO_PDV"/>
            <map field="originalPrice" path="data.PRECO_NORMAL"/>
          </step>
        </mupa-studio-query>
    """.trimIndent()

    @Test
    fun `amostra real on-way superkoch (2 steps, headers, body, mapping)`() {
        val config = PriceConfigXmlImporter.parse(amostraReal)

        assertTrue(config != null)
        assertEquals("generic", config!!.integration)
        assertEquals(8000L, config.timeoutMs)
        assertEquals(2, config.steps.size)

        val auth = config.steps[0]
        assertEquals("authenticate", auth.type)
        assertEquals("POST", auth.method)
        assertEquals("https://apionway.superkoch.com.br/on-way/auth/login", auth.url)
        assertEquals("application/json", auth.headers["Content-Type"])
        assertEquals("application/json", auth.headers["Accept"])
        assertTrue(auth.body!!.contains("\${studio_user}"))
        assertEquals("data.token", auth.mapping["access_token"])

        val lookup = config.steps[1]
        assertEquals("lookup_price", lookup.type)
        assertEquals("GET", lookup.method)
        assertEquals("https://apionway.superkoch.com.br/on-way/product/ean/\${ean}/store/18", lookup.url)
        assertEquals("Bearer \${access_token}", lookup.headers["Authorization"])
        assertEquals("data.description", lookup.mapping["description"])
        assertEquals("data.PRECO_PDV", lookup.mapping["price"])
        assertEquals("data.PRECO_NORMAL", lookup.mapping["originalPrice"])
    }

    @Test
    fun `xml em branco retorna null`() {
        assertNull(PriceConfigXmlImporter.parse(""))
        assertNull(PriceConfigXmlImporter.parse("   "))
    }

    @Test
    fun `xml invalido (tag raiz errada) retorna null`() {
        assertNull(PriceConfigXmlImporter.parse("<not-a-query><step/></not-a-query>"))
    }

    @Test
    fun `xml mal formado retorna null em vez de lancar excecao`() {
        assertNull(PriceConfigXmlImporter.parse("<mupa-studio-query><step></mupa-studio-query>"))
    }

    @Test
    fun `integration ausente usa generic como padrao`() {
        val xml = """
            <mupa-studio-query timeout-ms="5000">
              <step type="lookup_price" method="GET" url="https://example.com/p/${'$'}{ean}">
                <map field="price" path="data.price"/>
              </step>
            </mupa-studio-query>
        """.trimIndent()

        val config = PriceConfigXmlImporter.parse(xml)
        assertTrue(config != null)
        assertEquals("generic", config!!.integration)
        assertEquals(1, config.steps.size)
    }
}
