package com.mupa.player.enterprise.price

import org.json.JSONObject
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Importa o "XML da consulta" exportado pelo Mupa Studio (botão "XML da consulta",
 * `queryToXml()` em `apps/web/src/bundle.ts`) pro mesmo [PriceConfig] que o manifesto normal usa
 * — reaproveita [PriceConfigParser] por inteiro, só traduz o XML pro JSON que ele já entende, sem
 * duplicar regras de parsing. Manual por enquanto (colado em Configurações > "Importar XML da
 * Consulta", ver SettingsManager.customQueryXml) — mais pra frente o Mupa Connect distribui o
 * mesmo XML remotamente.
 *
 * Formato esperado (raiz `<mupa-studio-query>`):
 * ```
 * <mupa-studio-query version="1" integration="generic" timeout-ms="8000">
 *   <image enabled="true" kind="foto"/>          (ignorado aqui — foto/arte já resolvidas à parte)
 *   <step type="authenticate" method="POST" url="...">
 *     <header name="Content-Type"><![CDATA[application/json]]></header>
 *     <body><![CDATA[{"username":"..."}]]></body>
 *     <map field="access_token" path="data.token"/>
 *   </step>
 * </mupa-studio-query>
 * ```
 */
object PriceConfigXmlImporter {
    fun parse(xml: String): PriceConfig? {
        val trimmed = xml.trim()
        if (trimmed.isBlank()) return null

        val root = runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            builder.parse(trimmed.byteInputStream(Charsets.UTF_8)).documentElement
        }.getOrNull() ?: return null

        if (root.tagName != "mupa-studio-query") return null

        val json = JSONObject()
        json.put("integration", root.getAttribute("integration").trim().ifBlank { "generic" })
        val timeoutMs = root.getAttribute("timeout-ms").trim().toLongOrNull()
        if (timeoutMs != null) json.put("timeout", timeoutMs)

        val steps = org.json.JSONArray()
        childElements(root, "step").forEach { stepEl ->
            val stepJson = JSONObject()
            stepJson.put("type", stepEl.getAttribute("type").trim())
            stepJson.put("url", stepEl.getAttribute("url").trim())
            stepJson.put("method", stepEl.getAttribute("method").trim().ifBlank { "GET" })

            val headers = JSONObject()
            childElements(stepEl, "header").forEach { headerEl ->
                val name = headerEl.getAttribute("name").trim()
                if (name.isNotBlank()) headers.put(name, headerEl.textContent?.trim().orEmpty())
            }
            if (headers.length() > 0) stepJson.put("headers", headers)

            val mapping = JSONObject()
            childElements(stepEl, "map").forEach { mapEl ->
                val field = mapEl.getAttribute("field").trim()
                val path = mapEl.getAttribute("path").trim()
                if (field.isNotBlank()) mapping.put(field, path)
            }
            if (mapping.length() > 0) stepJson.put("mapping", mapping)

            childElements(stepEl, "body").firstOrNull()?.textContent?.trim()?.let { body ->
                if (body.isNotEmpty()) stepJson.put("body", body)
            }

            steps.put(stepJson)
        }
        json.put("steps", steps)

        return runCatching { PriceConfigParser.parse(json.toString()) }.getOrNull()
    }

    private fun childElements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        val result = ArrayList<Element>(nodes.length)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            // getElementsByTagName é recursivo (pega netos também); só queremos filhos diretos.
            if (node is Element && node.parentNode === parent) result += node
        }
        return result
    }
}
