package com.github.kr328.clash.service.clash.module

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.BaseService
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.clash.common.enqueueEvent
import com.github.kr328.clash.service.clash.common.receiveBroadcast
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.io.File
import java.util.UUID

class ConfigurationModule(service: BaseService) : Module<ConfigurationModule.LoadException>(service) {
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

                val baseDir = service.importedDir
                val configFile = File(baseDir, active.uuid.toString())

                if (configFile.exists()) {
                    applyCustomTuning(configFile)
                }

                Clash.load(configFile).await()

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
                    "tcp-concurrent: true\n$content"
                }
            }

            // 2. Unified Delay
            if (store.unifiedDelay) {
                content = if (content.contains("unified-delay:")) {
                    content.replace(Regex("unified-delay:\\s*(true|false)"), "unified-delay: true")
                } else {
                    "unified-delay: true\n$content"
                }
            }

            // 3. Domain Sniffer
            if (store.enableSniffer) {
                val snifferBlock = "sniffer:\n  enable: true\n  sniff:\n    TLS:\n      ports: [443, 8443]\n    HTTP:\n      ports: [80, 8080-8880]\n"
                if (content.contains("sniffer:")) {
                    content = content.replace(Regex("sniffer:\\s*\\n(\\s+.*\\n)*"), snifferBlock)
                } else {
                    content = "$snifferBlock$content"
                }
            }

            // 4. DNS Configuration (DoH + Custom Fake-IP Filter)
            if (store.enableDoh || store.enableFakeIpFilter) {
                val targetDoh = if (store.enableDoh) {
                    val url = store.dohUrl.trim()
                    if (url.isBlank()) "https://1.1.1.1/dns-query" else url
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
                            parsed.append("    - '").append(trimmed).append("'\n")
                        }
                    }

                    filterYaml = "  fake-ip-filter:\n    - '+.stun.*'\n    - '+.msftconnecttest.com'\n    - '+.msftncsi.com'\n    - 'time.*.com'\n    - 'ntp.*.com'\n$parsed"
                }

                val dnsBlock = "dns:\n  enable: true\n  enhanced-mode: fake-ip\n  nameserver:\n    - $targetDoh\n    - 8.8.8.8\n  fallback:\n    - https://1.0.0.1/dns-query\n    - https://9.9.9.9/dns-query\n$filterYaml"

                if (content.contains("dns:")) {
                    content = content.replace(Regex("dns:\\s*\\n(\\s+.*\\n)*"), dnsBlock)
                } else {
                    content = "$content\n$dnsBlock"
                }
            }

            configFile.writeText(content)
            Log.d("Config successfully patched with active network tunings & fake-ip filters")
        } catch (e: Exception) {
            Log.e("Failed to apply network tunings: ${e.message}")
        }
    }
}
