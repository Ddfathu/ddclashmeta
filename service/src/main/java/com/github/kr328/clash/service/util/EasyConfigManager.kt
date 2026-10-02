package com.github.kr328.clash.service.util

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.util.regex.Pattern

object EasyConfigManager {

        private const val HEADER_TEMPLATE = """mixed-port: 7890
allow-lan: false
mode: rule
log-level: silent
ipv6: false
tcp-concurrent: true
find-process-mode: off
global-client-fingerprint: chrome

dns:
  enable: true
  listen: 0.0.0.0:1053
  ipv6: false
  enhanced-mode: redir-host
  nameserver:
    - https://dns.google/dns-query
    - 1.1.1.1
    - 8.8.8.8
  direct-nameserver:
    - system

proxies:
"""

    // 1. Ekstrak hanya nama node (- name: "...")
    fun extractProxyNames(rawNodes: String): List<String> {
        val names = mutableListOf<String>()
        val pattern = Pattern.compile("^[ \\t]*-[ \\t]+name:[ \\t]*[\"']?([^\"'\\r\\n]+)[\"']?", Pattern.MULTILINE)
        val matcher = pattern.matcher(rawNodes)
        while (matcher.find()) {
            val name = matcher.group(1)?.trim()
            if (!name.isNullOrEmpty() && !names.contains(name)) {
                names.add(name)
            }
        }
        return names
    }

    // 2. Parser URL murni -> HANYA menghasilkan blok "- name: ..." tanpa header/rules
    fun parseLinkToNodeYaml(link: String): String? {
        val trimmed = link.trim()
        return when {
            trimmed.startsWith("vless://") -> parseVless(trimmed)
            trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
            trimmed.startsWith("vmess://") -> parseVmess(trimmed)
            else -> null
        }
    }

    private fun parseVless(link: String): String? {
        return runCatching {
            val uri = Uri.parse(link)
            val name = if (!uri.fragment.isNullOrEmpty()) URLDecoder.decode(uri.fragment, "UTF-8") else "VLESS-${uri.host}"
            val uuid = uri.userInfo ?: return null
            val server = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 443
            val type = uri.getQueryParameter("type") ?: "ws"
            val security = uri.getQueryParameter("security") ?: "none"
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: ""
            val path = uri.getQueryParameter("path") ?: "/"
            val host = uri.getQueryParameter("host") ?: sni

            val sb = StringBuilder()
            sb.append("- name: \"").append(name).append("\"\n")
            sb.append("  type: vless\n")
            sb.append("  server: ").append(server).append("\n")
            sb.append("  port: ").append(port).append("\n")
            sb.append("  uuid: ").append(uuid).append("\n")
            sb.append("  udp: true\n")
            if (security == "tls") {
                sb.append("  tls: true\n")
                sb.append("  skip-cert-verify: true\n")
                if (sni.isNotEmpty()) sb.append("  servername: ").append(sni).append("\n")
            }
            if (type == "ws") {
                sb.append("  network: ws\n")
                sb.append("  ws-opts:\n")
                sb.append("    path: \"").append(path).append("\"\n")
                if (host.isNotEmpty()) {
                    sb.append("    headers:\n")
                    sb.append("      Host: ").append(host).append("\n")
                }
            }
            sb.toString().trimEnd()
        }.getOrNull()
    }

    private fun parseTrojan(link: String): String? {
        return runCatching {
            val uri = Uri.parse(link)
            val name = if (!uri.fragment.isNullOrEmpty()) URLDecoder.decode(uri.fragment, "UTF-8") else "Trojan-${uri.host}"
            val password = uri.userInfo ?: return null
            val server = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 443
            val type = uri.getQueryParameter("type") ?: "ws"
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: ""
            val path = uri.getQueryParameter("path") ?: "/"
            val host = uri.getQueryParameter("host") ?: sni

            val sb = StringBuilder()
            sb.append("- name: \"").append(name).append("\"\n")
            sb.append("  type: trojan\n")
            sb.append("  server: ").append(server).append("\n")
            sb.append("  port: ").append(port).append("\n")
            sb.append("  password: ").append(password).append("\n")
            sb.append("  udp: true\n")
            sb.append("  skip-cert-verify: true\n")
            if (sni.isNotEmpty()) sb.append("  sni: ").append(sni).append("\n")
            if (type == "ws") {
                sb.append("  network: ws\n")
                sb.append("  ws-opts:\n")
                sb.append("    path: \"").append(path).append("\"\n")
                if (host.isNotEmpty()) {
                    sb.append("    headers:\n")
                    sb.append("      Host: ").append(host).append("\n")
                }
            }
            sb.toString().trimEnd()
        }.getOrNull()
    }

    private fun parseVmess(link: String): String? {
        return runCatching {
            val raw = link.substringAfter("vmess://")
            val jsonStr = String(Base64.decode(raw, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE))
            val json = JSONObject(jsonStr)

            val name = json.optString("ps", "VMess")
            val server = json.optString("add")
            val port = json.optInt("port", 443)
            val uuid = json.optString("id")
            val aid = json.optInt("aid", 0)
            val net = json.optString("net", "ws")
            val type = json.optString("type", "none")
            val host = json.optString("host", "")
            val path = json.optString("path", "/")
            val tls = json.optString("tls", "")

            val sb = StringBuilder()
            sb.append("- name: \"").append(name).append("\"\n")
            sb.append("  type: vmess\n")
            sb.append("  server: ").append(server).append("\n")
            sb.append("  port: ").append(port).append("\n")
            sb.append("  uuid: ").append(uuid).append("\n")
            sb.append("  alterId: ").append(aid).append("\n")
            sb.append("  cipher: auto\n")
            sb.append("  udp: true\n")
            if (tls == "tls") {
                sb.append("  tls: true\n")
                sb.append("  skip-cert-verify: true\n")
                if (host.isNotEmpty()) sb.append("  servername: ").append(host).append("\n")
            }
            if (net == "ws") {
                sb.append("  network: ws\n")
                sb.append("  ws-opts:\n")
                sb.append("    path: \"").append(path).append("\"\n")
                if (host.isNotEmpty()) {
                    sb.append("    headers:\n")
                    sb.append("      Host: ").append(host).append("\n")
                }
            }
            sb.toString().trimEnd()
        }.getOrNull()
    }

    // 3. Gabungkan semua node mentah menjadi config utuh (dipanggil saat tombol SIMPAN ditekan)
    fun buildFullConfig(rawNodes: String): String {
        val sanitizedNodes = rawNodes
            .replace("\u201C", "\"")
            .replace("\u201D", "\"")
            .replace("\u2018", "'")
            .replace("\u2019", "'")
            .replace("\u00A0", " ")
            .replace("\t", "  ")
            .trim()

        val names = extractProxyNames(sanitizedNodes)

        val sb = StringBuilder()
        sb.append(HEADER_TEMPLATE)
        sb.append(sanitizedNodes).append("\n\n")

        sb.append("proxy-groups:\n")
        sb.append("  - name: PROXIES\n")
        sb.append("    type: select\n")
        sb.append("    proxies:\n")
        for (name in names) {
            sb.append("      - \"").append(name).append("\"\n")
        }
        sb.append("      - DIRECT\n\n")

        sb.append("rules:\n")
        sb.append("  - DST-PORT,53,DIRECT\n  - MATCH,PROXIES\n")

        return sb.toString()
    }

    // 4. Ambil hanya bagian proxies: dari file config.yaml yang tersimpan
    fun extractRawNodes(fullConfig: String): String {
        val proxiesIndex = fullConfig.indexOf("proxies:\n")
        val groupsIndex = fullConfig.indexOf("proxy-groups:\n")
        if (proxiesIndex != -1 && groupsIndex != -1 && groupsIndex > proxiesIndex) {
            return fullConfig.substring(proxiesIndex + "proxies:\n".length, groupsIndex).trim()
        }
        return fullConfig.trim()
    }
}
