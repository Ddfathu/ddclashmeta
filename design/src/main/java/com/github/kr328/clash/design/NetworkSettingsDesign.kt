package com.github.kr328.clash.design

import android.content.Context
import android.os.Build
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.launch

class NetworkSettingsDesign(
    context: Context,
    uiStore: UiStore,
    srvStore: ServiceStore,
    running: Boolean,
) : Design<NetworkSettingsDesign.Request>(context) {
    enum class Request {
        StartAccessControlList
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            val vpnDependencies: MutableList<Preference> = mutableListOf()
            val dohDependencies: MutableList<Preference> = mutableListOf()
            val fakeIpDependencies: MutableList<Preference> = mutableListOf()

            val vpn = switch(
                value = uiStore::enableVpn,
                icon = R.drawable.ic_baseline_vpn_lock,
                title = R.string.route_system_traffic,
                summary = R.string.routing_via_vpn_service
            ) {
                listener = OnChangedListener {
                    vpnDependencies.forEach {
                        it.enabled = uiStore.enableVpn
                    }
                    dohDependencies.forEach {
                        it.enabled = uiStore.enableVpn && srvStore.enableDoh
                    }
                    fakeIpDependencies.forEach {
                        it.enabled = uiStore.enableVpn && srvStore.enableFakeIpFilter
                    }
                }
            }

            // KATEGORI 1: OPTIMASI PERFORMA JARINGAN
            category(R.string.network_tuning_category)

            switch(
                value = srvStore::enableDoh,
                title = R.string.enable_doh,
                summary = R.string.enable_doh_summary,
            ) {
                vpnDependencies.add(this)
                listener = OnChangedListener {
                    dohDependencies.forEach { item ->
                        item.enabled = srvStore.enableDoh
                    }
                }
            }

            editableText(
                value = srvStore::dohUrl,
                adapter = StringAdapter,
                title = R.string.doh_url,
            ) {
                placeholder = context.getString(R.string.doh_url_summary)
                vpnDependencies.add(this)
                dohDependencies.add(this)
            }

            switch(
                value = srvStore::tcpConcurrent,
                title = R.string.tcp_concurrent,
                summary = R.string.tcp_concurrent_summary,
            ) {
                vpnDependencies.add(this)
            }

            switch(
                value = srvStore::enableSniffer,
                title = R.string.enable_sniffer,
                summary = R.string.enable_sniffer_summary,
            ) {
                vpnDependencies.add(this)
            }

            switch(
                value = srvStore::unifiedDelay,
                title = R.string.unified_delay,
                summary = R.string.unified_delay_summary,
            ) {
                vpnDependencies.add(this)
            }

            switch(
                value = srvStore::enableFakeIpFilter,
                title = R.string.enable_fake_ip_filter,
                summary = R.string.enable_fake_ip_filter_summary,
            ) {
                vpnDependencies.add(this)
                listener = OnChangedListener {
                    fakeIpDependencies.forEach { item ->
                        item.enabled = srvStore.enableFakeIpFilter
                    }
                }
            }

            editableText(
                value = srvStore::customFakeIpFilter,
                adapter = StringAdapter,
                title = R.string.fake_ip_filter_list,
            ) {
                placeholder = context.getString(R.string.fake_ip_filter_list_summary)
                vpnDependencies.add(this)
                fakeIpDependencies.add(this)
            }

            // KATEGORI 2: VPN SERVICE BAWAAN
            category(R.string.vpn_service_options)

            switch(
                value = srvStore::bypassPrivateNetwork,
                title = R.string.bypass_private_network,
                summary = R.string.bypass_private_network_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::dnsHijacking,
                title = R.string.dns_hijacking,
                summary = R.string.dns_hijacking_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::allowBypass,
                title = R.string.allow_bypass,
                summary = R.string.allow_bypass_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::allowIpv6,
                title = R.string.allow_ipv6,
                summary = R.string.allow_ipv6_summary,
                configure = vpnDependencies::add,
            )

            if (Build.VERSION.SDK_INT >= 29) {
                switch(
                    value = srvStore::systemProxy,
                    title = R.string.system_proxy,
                    summary = R.string.system_proxy_summary,
                    configure = vpnDependencies::add,
                )
            }

            selectableList(
                value = srvStore::tunStackMode,
                values = arrayOf(
                    "system",
                    "gvisor",
                    "mixed",
                    "mips"
                ),
                valuesText = arrayOf(
                    R.string.tun_stack_system,
                    R.string.tun_stack_gvisor,
                    R.string.tun_stack_mixed,
                    R.string.tun_stack_mips
                ),
                title = R.string.tun_stack_mode,
                configure = vpnDependencies::add,
            )

            selectableList(
                value = srvStore::accessControlMode,
                values = AccessControlMode.values(),
                valuesText = arrayOf(
                    R.string.allow_all_apps,
                    R.string.allow_selected_apps,
                    R.string.deny_selected_apps
                ),
                title = R.string.access_control_mode,
                configure = vpnDependencies::add,
            )

            clickable(
                title = R.string.access_control_packages,
                summary = R.string.access_control_packages_summary,
            ) {
                clicked {
                    requests.trySend(Request.StartAccessControlList)
                }
            }

            if (running) {
                vpn.enabled = false

                vpnDependencies.forEach {
                    it.enabled = false
                }
            } else {
                vpn.listener?.onChanged()
                dohSwitch.listener?.onChanged()
                fakeIpSwitch.listener?.onChanged()
            }
        }

        binding.content.addView(screen.root)

        if (running) {
            launch {
                showToast(R.string.options_unavailable, ToastDuration.Indefinite)
            }
        }
    }
}
