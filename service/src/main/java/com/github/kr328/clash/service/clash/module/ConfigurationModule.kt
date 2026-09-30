package com.github.kr328.clash.service.clash.module

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.BaseService
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.clash.common.enqueueEvent
import com.github.kr328.clash.service.clash.common.receiveBroadcast
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.io.File
import java.util.*

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

                val configFile = service.importedDir.resolve(active.uuid.toString())

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
                    "tcp-concurrent: true\n" + content
                }
            }

            // 2. Unified Delay
            if (store.unifiedDelay) {
                content = if (content.contains("unified-delay:")) {
                    content.replace(Regex("unified-delay:\\s*(true|false)"), "unified-delay: true")
                } else {
                    "unified-delay: true\n" + content
                }
            }

            // 3. Domain Sniffer
            if (store.enableSniffer) {
                val snifferBlock = """
sniffer:
  enable: true
  sniff:
    TLS:
      ports: [443, 8443]
    HTTP:
      ports: [80, 8080-8880]
"""
                if (content.contains("sniffer:")) {
                    content = content.replace(Regex("sniffer:\\s*\\n(\\s+.*\\n)*"), snifferBlock.trimStart() + "\n")
                } else {
                    content = snifferBlock.trimStart() + "\n" + content
                }
            }

            // 4. DNS Configuration (DoH + Custom Fake-IP Filter)
            if (store.enableDoh || store.enableFakeIpFilter) {
                val targetDoh = if (store.enableDoh) {
                    store.dohUrl.trim().ifBlank { "https://1.1.1.1/dns-query" }
                } else {
                    "1.1.1.1"
                }

                var filterYaml = ""
                if (store.enableFakeIpFilter) {
                    val domains = store.customFakeIpFilter.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n") { "    - '$it'" }

                    val baseFilters = """
    - '+.stun.*'
    - '+.msftconnecttest.com'
    - '+.msftncsi.com'
    - 'time.*.com'
    - 'ntp.*.com'
"""
                    filterYaml = """
  fake-ip-filter:
$baseFilters
$domains
"""
                }

                val dnsBlock = """
dns:
  enable: true
  enhanced-mode: fake-ip
  nameserver:
    - $targetDoh
    - 8.8.8.8
  fallback:
    - https://1.0.0.1/dns-query
    - https://9.9.9.9/dns-query$filterYaml
"""
                if (content.contains("dns:")) {
                    content = content.replace(Regex("dns:\\s*\\n(\\s+.*\\n)*"), dnsBlock.trimStart() + "\n")
                } else {
                    content = content + "\n" + dnsBlock
                }
            }

            configFile.writeText(content)
            Log.d("Config successfully patched with active network tunings & fake-ip filters")
        } catch (e: Exception) {
            Log.e("Failed to apply network tunings: ${e.message}")
        }
    }
}
