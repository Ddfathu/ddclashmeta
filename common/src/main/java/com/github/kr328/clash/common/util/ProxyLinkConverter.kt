package com.github.kr328.clash.common.util

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ProxyLinkConverter {

    fun isProxyLink(text: String): Boolean {
        val t = text.trim()
        return t.contains("vmess://") || t.contains("vless://") || t.contains("trojan://")
    }

    fun parseNodes(rawText: String, startIndex: Int = 1): List<Pair<String, String>> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<Pair<String, String>>()
        var idx = startIndex

        for (line in lines) {
            try {
                when {
                    line.startsWith("vmess://", ignoreCase = true) -> {
                        parseVmess(line, idx)?.let { result.add(it); idx++ }
                    }
                    line.startsWith("vless://", ignoreCase = true) -> {
                        parseVless(line, idx)?.let { result.add(it); idx++ }
                    }
                    line.startsWith("trojan://", ignoreCase = true) -> {
                        parseTrojan(line, idx)?.let { result.add(it); idx++ }
                    }
                }
            } catch (e: Exception) {}
        }
        return result
    }

    fun appendOrGenerate(existingContent: String?, rawLinks: String): String {
        val newNodes = parseNodes(rawLinks)
        if (newNodes.isEmpty()) {
            throw IllegalArgumentException("Tidak ditemukan link proxy yang valid")
        }

        val existing = existingContent?.trim().orEmpty()
        if (existing.isEmpty() || !existing.contains("proxies:")) {
            return generateFullYaml(newNodes)
        }

        val lines = existing.lines().toMutableList()
        val finalLines = mutableListOf<String>()

        var inProxies = false
        var proxiesAppended = false

        for (i in lines.indices) {
            val line = lines[i]

            if (line.trim().startsWith("proxies:")) {
                inProxies = true
                finalLines.add(line)
                continue
            }

            if (inProxies && (line.startsWith("proxy-groups:") || line.startsWith("dns:") || line.startsWith("rules:"))) {
                if (!proxiesAppended) {
                    newNodes.forEach { finalLines.add(it.first.trimEnd()) }
                    proxiesAppended = true
                }
                inProxies = false
            }

            if (line.contains("name: PROXIES")) {
                finalLines.add(line)
                var j = i + 1
                while (j < lines.size && !lines[j].trim().startsWith("proxies:")) {
                    finalLines.add(lines[j])
                    j++
                }
                if (j < lines.size && lines[j].trim().startsWith("proxies:")) {
                    finalLines.add(lines[j])
                    newNodes.forEach {
                        finalLines.add("      - \"${it.second}\"")
                    }
                }
                continue
            }

            finalLines.add(line)
        }

        if (!proxiesAppended) {
            newNodes.forEach { finalLines.add(it.first.trimEnd()) }
        }

        return finalLines.joinToString("\n")
    }

    private fun generateFullYaml(nodes: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        sb.appendLine("port: 7890")
        sb.appendLine("socks-port: 7891")
        sb.appendLine("allow-lan: false")
        sb.appendLine("mode: rule")
        sb.appendLine("log-level: info")
        sb.appendLine("ipv6: false")
        sb.appendLine()
        sb.appendLine("proxies:")
        nodes.forEach { sb.append(it.first) }
        sb.appendLine()
        sb.appendLine("proxy-groups:")
        sb.appendLine("  - name: PROXIES")
        sb.appendLine("    type: select")
        sb.appendLine("    url: \"https://www.google.com/generate_204\"")
        sb.appendLine("    interval: 300")
        sb.appendLine("    proxies:")
        nodes.forEach { sb.appendLine("      - \"${it.second}\"") }
        sb.appendLine("      - DIRECT")
        sb.appendLine()
        sb.appendLine("dns:")
        sb.appendLine("  enable: true")
        sb.appendLine("  ipv6: false")
        sb.appendLine("  enhanced-mode: fake-ip")
        sb.appendLine("  fake-ip-range: 198.18.0.1/16")
        sb.appendLine("  nameserver:")
        sb.appendLine("    - 8.8.8.8")
        sb.appendLine("    - 1.1.1.1")
        sb.appendLine("    - https://dns.google/dns-query")
        sb.appendLine("  fallback:")
        sb.appendLine("    - 8.8.4.4")
        sb.appendLine("    - 1.0.0.1")
        sb.appendLine()
        sb.appendLine("rules:")
        sb.appendLine("  - MATCH,PROXIES")

        return sb.toString()
    }

    private fun parseVmess(link: String, index: Int): Pair<String, String>? {
        val b64 = link.substringAfter("vmess://").trim()
        val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
        val json = JSONObject(jsonStr)

        val name = json.optString("ps", "vmess-$index").replace("\"", "")
        val server = json.optString("add")
        val port = json.optInt("port")
        val uuid = json.optString("id")
        val aid = json.optInt("aid", 0)
        val net = json.optString("net", "tcp")
        val host = json.optString("host", "")
        val path = json.optString("path", "/")
        val tls = json.optString("tls") == "tls"
        val sni = json.optString("sni", host)

        val sb = StringBuilder()
        sb.appendLine("  - name: \"$name\"")
        sb.appendLine("    type: vmess")
        sb.appendLine("    server: $server")
        sb.appendLine("    port: $port")
        sb.appendLine("    uuid: $uuid")
        sb.appendLine("    alterId: $aid")
        sb.appendLine("    cipher: auto")
        sb.appendLine("    udp: true")
        if (tls) {
            sb.appendLine("    tls: true")
            sb.appendLine("    skip-cert-verify: true")
            if (sni.isNotEmpty()) sb.appendLine("    servername: $sni")
        }
        sb.appendLine("    network: $net")
        if (net == "ws") {
            sb.appendLine("    ws-opts:")
            sb.appendLine("      path: \"$path\"")
            if (host.isNotEmpty()) {
                sb.appendLine("      headers:")
                sb.appendLine("        Host: \"$host\"")
            }
        }
        return Pair(sb.toString(), name)
    }

    private fun parseVless(link: String, index: Int): Pair<String, String>? {
        val uri = Uri.parse(link)
        val server = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val uuid = uri.userInfo ?: return null
        val fragment = uri.fragment ?: "vless-$index"
        val name = URLDecoder.decode(fragment, "UTF-8").replace("\"", "")

        val type = uri.getQueryParameter("type") ?: "tcp"
        val security = uri.getQueryParameter("security") ?: "none"
        val sni = uri.getQueryParameter("sni") ?: server
        val path = uri.getQueryParameter("path") ?: "/"
        val host = uri.getQueryParameter("host") ?: ""

        val sb = StringBuilder()
        sb.appendLine("  - name: \"$name\"")
        sb.appendLine("    type: vless")
        sb.appendLine("    server: $server")
        sb.appendLine("    port: $port")
        sb.appendLine("    uuid: $uuid")
        sb.appendLine("    udp: true")
        if (security == "tls") {
            sb.appendLine("    tls: true")
            sb.appendLine("    skip-cert-verify: true")
            sb.appendLine("    servername: $sni")
        }
        sb.appendLine("    network: $type")
        if (type == "ws") {
            sb.appendLine("    ws-opts:")
            sb.appendLine("      path: \"$path\"")
            if (host.isNotEmpty()) {
                sb.appendLine("      headers:")
                sb.appendLine("        Host: \"$host\"")
            }
        }
        return Pair(sb.toString(), name)
    }

    private fun parseTrojan(link: String, index: Int): Pair<String, String>? {
        val uri = Uri.parse(link)
        val server = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val password = uri.userInfo ?: return null
        val fragment = uri.fragment ?: "trojan-$index"
        val name = URLDecoder.decode(fragment, "UTF-8").replace("\"", "")
        val sni = uri.getQueryParameter("sni") ?: server
        val type = uri.getQueryParameter("type") ?: "tcp"
        val host = uri.getQueryParameter("host") ?: ""
        val path = uri.getQueryParameter("path") ?: "/"

        val sb = StringBuilder()
        sb.appendLine("  - name: \"$name\"")
        sb.appendLine("    type: trojan")
        sb.appendLine("    server: $server")
        sb.appendLine("    port: $port")
        sb.appendLine("    password: \"$password\"")
        sb.appendLine("    udp: true")
        sb.appendLine("    skip-cert-verify: true")
        sb.appendLine("    sni: $sni")
        sb.appendLine("    network: $type")
        if (type == "ws") {
            sb.appendLine("    ws-opts:")
            sb.appendLine("      path: \"$path\"")
            if (host.isNotEmpty()) {
                sb.appendLine("      headers:")
                sb.appendLine("        Host: \"$host\"")
            }
        }
        return Pair(sb.toString(), name)
    }
}
