package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var activeProfile: UUID? by store.typedString(
        key = "active_profile",
        from = { if (it.isBlank()) null else UUID.fromString(it) },
        to = { it?.toString() ?: "" }
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptAll,
        values = AccessControlMode.values()
    )

    var accessControlPackages by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    var dnsHijacking by store.boolean(
        key = "dns_hijacking",
        defaultValue = true
    )

    // FITUR OPTIMASI JARINGAN (Default: False / Mati Polosan)
    var enableDoh by store.boolean(
        key = "enable_doh",
        defaultValue = false
    )

    var dohUrl by store.string(
        key = "doh_url",
        defaultValue = "https://1.1.1.1/dns-query"
    )

    var tcpConcurrent by store.boolean(
        key = "tcp_concurrent",
        defaultValue = false
    )

    var enableSniffer by store.boolean(
        key = "enable_sniffer",
        defaultValue = false
    )

    var unifiedDelay by store.boolean(
        key = "unified_delay",
        defaultValue = false
    )

    var enableFakeIpFilter by store.boolean(
        key = "enable_fake_ip_filter",
        defaultValue = false
    )

    var customFakeIpFilter by store.string(
        key = "custom_fake_ip_filter",
        defaultValue = "*.bca.co.id, *.bri.co.id, *.mandiri.co.id, *.grab.com, *.gojek.com, *.mobilelegends.com"
    )

    var systemProxy by store.boolean(
        key = "system_proxy",
        defaultValue = true
    )

    var allowBypass by store.boolean(
        key = "allow_bypass",
        defaultValue = true
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "mixed"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = true
    )
}
