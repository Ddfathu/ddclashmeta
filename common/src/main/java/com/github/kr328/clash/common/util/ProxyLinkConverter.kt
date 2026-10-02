package com.github.kr328.clash.common.util

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ProxyLinkConverter {

    fun isProxyLink(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("vmess://") ||
                trimmed.startsWith("vless://") ||
                trimmed.startsWith("trojan://") ||
                trimmed.startsWith("ss://")
    }

    fun generateStandaloneYaml(rawLinks: String): Pair<String, String> {
        val nodes = parseNodes(rawLinks)
        if (nodes.isEmpty()) throw IllegalArgumentException("Tidak ada node proxy yang valid ditemukan")

        val firstName = nodes.first().name
        val sb = StringBuilder()
        sb.append(getBaseConfigHeader())

        sb.append("\n\nproxies:\n")
        nodes.forEach { sb.append(it.toYamlBlock()).append("\n\n") }

        sb.append("proxy-groups:\n")
        sb.append("  - name: PROXIES\n")
        sb.append("    type: select\n")
        sb.append("    proxies:\n")
        nodes.forEach { sb.append("      - \"").append(it.name).append("\"\n") }
        sb.append("      - DIRECT\n\n")

        sb.append("rules:\n")
        sb.append("  - DST-PORT,53,DIRECT\n  - MATCH,PROXIES\n")

        return Pair(sb.toString(), firstName)
    }

    fun appendOrGenerateDirect(existingYaml: String?, rawLinks: String): String {
        val newNodes = parseNodes(rawLinks)
        if (newNodes.isEmpty()) throw IllegalArgumentException("Tidak ada proxy yang valid")

        if (existingYaml.isNullOrBlank() || !existingYaml.contains("proxies:")) {
            val (yaml, _) = generateStandaloneYaml(rawLinks)
            return yaml
        }

        val newProxiesYaml = buildString {
            newNodes.forEach {
                append(it.toYamlBlock())
                append("\n\n")
            }
        }

        val newGroupEntries = buildString {
            newNodes.forEach {
                append("      - \"").append(it.name).append("\"\n")
            }
        }

        var result = existingYaml

        result = if (result.contains("proxies:\n")) {
            result.replaceFirst("proxies:\n", "proxies:\n$newProxiesYaml")
        } else {
            result + "\n\nproxies:\n$newProxiesYaml"
        }

        result = when {
            result.contains("      - DIRECT") -> {
                result.replaceFirst("      - DIRECT", "$newGroupEntries      - DIRECT")
            }
            result.contains("    proxies:\n") -> {
                result.replaceFirst("    proxies:\n", "    proxies:\n$newGroupEntries")
            }
            else -> result
        }

        return result
    }

    private fun getBaseConfigHeader(): String {
        return """
mixed-port: 7890
allow-lan: false
mode: rule
log-level: silent
ipv6: false

dns:
  enable: true
  listen: 0.0.0.0:1053
  ipv6: false
  enhanced-mode: redir-host
  nameserver:
    - 1.1.1.1
    - 8.8.8.8
""".trimIndent()
    }

    private fun parseNodes(rawLinks: String): List<ProxyNode> {
        val list = mutableListOf<ProxyNode>()
        rawLinks.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { link ->
                runCatching {
                    when {
                        link.startsWith("vmess://") -> parseVmess(link)
                        link.startsWith("vless://") -> parseVless(link)
                        link.startsWith("trojan://") -> parseTrojan(link)
                        else -> null
                    }
                }.getOrNull()?.let { list.add(it) }
            }
        return list
    }

    private fun parseVmess(link: String): ProxyNode {
        val b64 = link.removePrefix("vmess://").trim()
        val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
        val json = JSONObject(jsonStr)

        val name = json.optString("ps", "vmess_node")
        val server = json.optString("add")
        val port = json.optInt("port")
        val uuid = json.optString("id")
        val alterId = json.optInt("aid", 0)
        val cipher = json.optString("scy", "auto").ifEmpty { "auto" }
        val net = json.optString("net", "tcp")
        val tls = json.optString("tls")
        val sni = json.optString("sni", json.optString("host", server))
        val path = json.optString("path", "/")

        return ProxyNode(
            name = name,
            type = "vmess",
            server = server,
            port = port,
            uuid = uuid,
            alterId = alterId,
            cipher = cipher,
            network = net,
            tls = tls.equals("tls", true),
            sni = sni,
            wsPath = path
        )
    }

    private fun parseVless(link: String): ProxyNode {
        val uri = Uri.parse(link)
        val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "vless_node"
        val server = uri.host ?: ""
        val port = uri.port
        val uuid = uri.userInfo ?: ""
        val net = uri.getQueryParameter("type") ?: "tcp"
        val security = uri.getQueryParameter("security") ?: "none"
        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: server
        val path = uri.getQueryParameter("path")?.let { URLDecoder.decode(it, "UTF-8") } ?: "/"

        return ProxyNode(
            name = name,
            type = "vless",
            server = server,
            port = port,
            uuid = uuid,
            network = net,
            tls = security.equals("tls", true) || security.equals("reality", true),
            sni = sni,
            wsPath = path
        )
    }

    private fun parseTrojan(link: String): ProxyNode {
        val uri = Uri.parse(link)
        val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "trojan_node"
        val server = uri.host ?: ""
        val port = uri.port
        val password = uri.userInfo ?: ""
        val sni = uri.getQueryParameter("sni") ?: server
        val net = uri.getQueryParameter("type") ?: "tcp"
        val path = uri.getQueryParameter("path")?.let { URLDecoder.decode(it, "UTF-8") } ?: "/"

        return ProxyNode(
            name = name,
            type = "trojan",
            server = server,
            port = port,
            password = password,
            network = net,
            tls = true,
            sni = sni,
            wsPath = path
        )
    }

    data class ProxyNode(
        val name: String,
        val type: String,
        val server: String,
        val port: Int,
        val uuid: String? = null,
        val password: String? = null,
        val alterId: Int? = null,
        val cipher: String? = null,
        val network: String = "tcp",
        val tls: Boolean = false,
        val sni: String? = null,
        val wsPath: String? = null
    ) {
        fun toYamlBlock(): String {
            val sb = StringBuilder()
            sb.append("  - name: \"").append(name.replace("\"", "\\\"")).append("\"\n")
            sb.append("    server: ").append(server).append("\n")
            sb.append("    type: ").append(type).append("\n")
            sb.append("    port: ").append(port).append("\n")

            if (uuid != null) sb.append("    uuid: ").append(uuid).append("\n")
            if (password != null) sb.append("    password: ").append(password).append("\n")
            if (alterId != null) sb.append("    alterId: ").append(alterId).append("\n")
            if (cipher != null) sb.append("    cipher: ").append(cipher).append("\n")

            if (tls) {
                sb.append("    tls: true\n")
                sb.append("    skip-cert-verify: true\n")
                if (!sni.isNullOrBlank()) {
                    sb.append("    servername: ").append(sni).append("\n")
                }
            }

            if (network.equals("ws", ignoreCase = true)) {
                sb.append("    network: ws\n")
                sb.append("    ws-opts:\n")
                sb.append("      path: \"").append(wsPath ?: "/").append("\"\n")
                if (!sni.isNullOrBlank()) {
                    sb.append("      headers:\n")
                    sb.append("        Host: ").append(sni).append("\n")
                }
            }

            sb.append("    udp: true")

            return sb.toString()
        }
    }
}
