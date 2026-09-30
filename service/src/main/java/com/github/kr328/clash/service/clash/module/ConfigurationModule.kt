package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.io.File
import java.util.UUID

class ConfigurationModule(service: Service) : Module<ConfigurationModule.LoadException>(service) {
    class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)

    override suspend fun run() {
        val profileLoaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(com.github.kr328.clash.common.constants.Intents.ACTION_PROFILE_LOADED)
            addAction(com.github.kr328.clash.common.constants.Intents.ACTION_PROFILE_CHANGED)
        }

        var loaded: UUID? = null

        reload.trySend(Unit)

        while (true) {
            val changed = select<UUID?> {
                reload.onReceive {
                    null
                }
                profileLoaded.onReceive {
                    it.getSerializableExtra(com.github.kr328.clash.common.constants.Intents.EXTRA_UUID) as? UUID
                }
            }

            try {
                val current = store.activeProfile
                    ?: throw NullPointerException("No profile selected")

                if (current == loaded && changed != null && changed != loaded)
                    continue

                loaded = current

                val active = ImportedDao().queryByUUID(current)
                    ?: throw NullPointerException("No profile selected")

                Clash.setAgeSecretKey(active.ageSecretKey?.takeIf { it.isNotBlank() })

                val targetFile: File = service.importedDir.resolve(active.uuid.toString())

                if (targetFile.exists()) {
                    applyCustomTuning(targetFile)
                }

                Clash.load(targetFile).await()

                val remove = SelectionDao().querySelections(active.uuid)
                    .filterNot { Clash.patchSelector(it.proxy, it.selected) }
                    .map { it.proxy }

                SelectionDao().removeSelections(active.uuid, remove)

                StatusProvider.currentProfile = active.name

                service.sendProfileLoaded(current)

                Log.d("Profile ${active.name} loaded")
            } catch (e: Exception) {
                return enqueueEvent(LoadException(e.message ?: "Unknown"))
            }
        }
    }

    private fun applyCustomTuning(configFile: File) {
        try {
            var content = configFile.readText()

            // 1. TCP Concurrent
            if (store.tcpConcurrent) {
                content = if (content.contains("tcp-concurrent:")) {
                    content.replace(Regex("tcp-concurrent:\\s*(true|false)"), "tcp-concurrent: true")
                } else {
                    "tcp-concurrent: true\\n$content"
                }
            }

            // 2. Unified Delay
            if (store.unifiedDelay) {
                content = if (content.contains("unified-delay:")) {
                    content.replace(Regex("unified-delay:\\s*(true|false)"), "unified-delay: true")
                } else {
                    "unified-delay: true\\n$content"
                }
            }

            // 3. Domain Sniffer
            if (store.enableSniffer) {
                val snifferBlock = "sniffer:\\n  enable: true\\n  sniff:\\n    TLS:\\n      ports: [443, 8443]\\n    HTTP:\\n      ports: [80, 8080-8880]\\n"
                if (!content.contains("sniffer:")) {
                    content = "$snifferBlock$content"
                }
            }

            // 4. DNS Configuration (DoH + Custom Fake-IP Filter)
            if (store.enableDoh || store.enableFakeIpFilter) {
                val targetDoh = if (store.enableDoh) {
                    val url = store.dohUrl.trim()
                    if (url.isEmpty()) "https://1.1.1.1/dns-query" else url
                } else {
                    "1.1.1.1"
                }

                var filterYaml = ""
                if (store.enableFakeIpFilter) {
                    val rawList = store.customFakeIpFilter.split(",")
                    val parsed = StringBuilder()
                    for (item in rawList) {
                        val trimmed = item.trim()
                        if (trimmed.isNotEmpty()) {
                            parsed.append("    - \x27").append(trimmed).append("\x27\\n")
                        }
                    }
                    filterYaml = "  fake-ip-filter:\\n    - \x27+.stun.*\x27\\n    - \x27+.msftconnecttest.com\x27\\n    - \x27+.msftncsi.com\x27\\n    - \x27time.*.com\x27\\n    - \x27ntp.*.com\x27\\n$parsed"
                }

                val dnsBlock = """
dns:
  enable: true
  ipv6: false
  enhanced-mode: fake-ip
  fake-ip-range: 198.18.0.1/16
  default-nameserver:
    - 8.8.8.8
    - 1.1.1.1
  nameserver:
    - $targetDoh
    - 8.8.8.8
    - 1.1.1.1
  fallback:
    - https://1.0.0.1/dns-query
$filterYaml""".trimIndent()

                // Hapus blok dns lama baris demi baris secara aman
                if (content.contains("dns:")) {
                    val lines = content.lines()
                    val resultLines = mutableListOf<String>()
                    var skipDns = false
                    for (line in lines) {
                        if (line.trim().startsWith("dns:")) {
                            skipDns = true
                            continue
                        }
                        if (skipDns) {
                            if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\\t")) {
                                skipDns = false
                                resultLines.add(line)
                            }
                        } else {
                            resultLines.add(line)
                        }
                    }
                    content = resultLines.joinToString("\\n") + "\\n\\n" + dnsBlock
                } else {
                    content = "$content\\n\\n$dnsBlock"
                }
            }

            configFile.writeText(content)
        } catch (_: Exception) {
            // Abaikan kegagalan agar tidak crash
        }
    }
}
