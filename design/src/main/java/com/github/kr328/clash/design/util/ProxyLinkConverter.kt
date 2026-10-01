package com.github.kr328.clash.design.util

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ProxyLinkConverter {

    fun isProxyLink(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("vmess://", ignoreCase = true) ||
                t.startsWith("vless://", ignoreCase = true) ||
                t.startsWith("trojan://", ignoreCase = true) ||
                t.startsWith("ss://", ignoreCase = true)
    }

    fun toClashYaml(rawText: String, profileName: String = "Imported-Nodes"): String {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val proxies = mutableListOf<String>()
        val proxyNames = mutableListOf<String>()

        var index = 1
        for (line in lines) {
            try {
                when {
                    line.startsWith("vmess://", ignoreCase = true) -> {
                        val yaml = parseVmess(line, index)
                        if (yaml != null) {
                            proxies.add(yaml.first)
                            proxyNames.add(yaml.second)
                            index++
                        }
                    }
                    line.startsWith("vless://", ignoreCase = true) -> {
                        val yaml = parseVless(line, index)
                        if (yaml != null) {
                            proxies.add(yaml.first)
                            proxyNames.add(yaml.second)
                            index++
                        }
                    }
                    line.startsWith("trojan://", ignoreCase = true) -> {
                        val yaml = parseTrojan(line, index)
                        if (yaml != null) {
                            proxies.add(yaml.first)
                            proxyNames.add(yaml.second)
                            index++
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (proxies.isEmpty()) {
            throw IllegalArgumentException("Tidak ditemukan link proxy yang valid")
        }

        val sb = StringBuilder()
        sb.appendLine("port: 7890")
        sb.appendLine("socks-port: 7891")
        sb.appendLine("allow-lan: false")
        sb.appendLine("mode: rule")
        sb.appendLine("log-level: silent")
        sb.appendLine("ipv6: false")
        sb.appendLine()
        sb.appendLine("dns:")
        sb.appendLine("  enable: true")
        sb.appendLine("  listen: 0.0.0.0:1053")
        sb.appendLine("  ipv6: false")
        sb.appendLine("  enhanced-mode: redir-host")
        sb.appendLine("  nameserver:")
        sb.appendLine("    - 1.1.1.1")
        sb.appendLine("    - 8.8.8.8")
        sb.appendLine()
        sb.appendLine("proxies:")
        proxies.forEach { sb.append(it) }
        sb.appendLine()
        sb.appendLine("proxy-groups:")
        sb.appendLine("  - name: PROXIES")
        sb.appendLine("    type: select")
        sb.appendLine("    proxies:")
        proxyNames.forEach { sb.appendLine("      - \"$it\"") }
        sb.appendLine("      - DIRECT")
        sb.appendLine()
        sb.appendLine("rules:")
        sb.appendLine("  - DST-PORT,53,DIRECT\n  - MATCH,PROXIES")

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
        val type = json.optString("type", "none")
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

        val sb = StringBuilder()
        sb.appendLine("  - name: \"$name\"")
        sb.appendLine("    type: trojan")
        sb.appendLine("    server: $server")
        sb.appendLine("    port: $port")
        sb.appendLine("    password: \"$password\"")
        sb.appendLine("    udp: true")
        sb.appendLine("    skip-cert-verify: true")
        sb.appendLine("    sni: $sni")
        return Pair(sb.toString(), name)
    }
}
