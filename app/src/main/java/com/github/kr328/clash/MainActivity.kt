package com.github.kr328.clash
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.util.UUID
import com.github.kr328.clash.FilesActivity
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.service.ProfileProcessor
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.util.EasyConfigManager
import com.github.kr328.clash.service.util.importedDir


import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.github.kr328.clash.service.model.Profile

import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp -> {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = clipboard.primaryClip
                            val clipText = if (clipData != null && clipData.itemCount > 0) {
                                clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                            } else ""

                            if (clipText.isEmpty()) {
                                Toast.makeText(this@MainActivity, "Clipboard kosong! Salin link proxy dulu.", Toast.LENGTH_SHORT).show()
                            } else {
                                GlobalScope.launch(Dispatchers.IO) {
                                    try {
                                        val lines = clipText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                                        val parsedNodes = mutableListOf<String>()
                                        for (line in lines) {
                                            val nodeYaml = EasyConfigManager.parseLinkToNodeYaml(line)
                                            if (nodeYaml != null) parsedNodes.add(nodeYaml)
                                        }

                                        val rawYaml = if (parsedNodes.isNotEmpty()) {
                                            parsedNodes.joinToString("\n\n")
                                        } else if (clipText.contains("proxies:")) {
                                            clipText
                                        } else {
                                            null
                                        }

                                        if (rawYaml == null) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "Format link/YAML di clipboard tidak valid!", Toast.LENGTH_SHORT).show()
                                            }
                                            return@launch
                                        }

                                        val proxyNames = EasyConfigManager.extractProxyNames(rawYaml)
                                        val targetUuid = UUID.randomUUID()
                                        val targetName = proxyNames.firstOrNull() ?: "Auto-${System.currentTimeMillis() % 10000}"

                                        val profileDir = this@MainActivity.importedDir.resolve(targetUuid.toString())
                                        profileDir.mkdirs()
                                        val fullConfig = EasyConfigManager.buildFullConfig(rawYaml)
                                        profileDir.resolve("config.yaml").writeText(fullConfig)

                                        val dao = ImportedDao()
                                        val newProfile = Imported(
                                            uuid = targetUuid,
                                            name = targetName,
                                            type = Profile.Type.File,
                                            source = "clipboard",
                                            interval = 0,
                                            upload = 0,
                                            download = 0,
                                            total = 0,
                                            expire = 0,
                                            createdAt = System.currentTimeMillis()
                                        )
                                        dao.insert(newProfile)
                                        ProfileProcessor.active(this@MainActivity, targetUuid)

                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "Profil $targetName berhasil dibuat & aktif!", Toast.LENGTH_SHORT).show()
                                            design.fetch()
                                            if (clashRunning) {
                                                stopClashService()
                                                design.startClash()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "Gagal import: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        val allProfiles = withProfile { queryAll() }
        val activeProfile = withProfile { queryActive() }
        setProfileName(activeProfile?.name)

        withContext(Dispatchers.Main) {
            setupProfilesList(allProfiles, activeProfile)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setTraffic(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }

    private fun MainDesign.setupProfilesList(profiles: List<Profile>, active: Profile?) {
        val rv = profilesRecyclerView
        rv.layoutManager = LinearLayoutManager(this@MainActivity)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = profiles.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(DesignR.layout.item_main_profile, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = profiles[position]
                val isActive = item.uuid == active?.uuid

                val tvTitle = holder.itemView.findViewById<TextView>(DesignR.id.profile_title)
                val ivIcon = holder.itemView.findViewById<ImageView>(DesignR.id.profile_icon)
                val tvLabel = holder.itemView.findViewById<TextView>(DesignR.id.profile_active_label)
                val btnEdit = holder.itemView.findViewById<ImageView>(DesignR.id.profile_edit)

                btnEdit?.setOnClickListener {
                    startActivity(FilesActivity::class.intent.setUUID(item.uuid))
                }

                tvTitle.text = item.name
                if (isActive) {
                    ivIcon.setImageResource(DesignR.drawable.ic_baseline_check_circle)
                    ivIcon.setColorFilter(0xFF00C853.toInt())
                    tvLabel.visibility = View.VISIBLE
                    holder.itemView.setBackgroundColor(0x1500C853)
                } else {
                    ivIcon.setImageResource(DesignR.drawable.ic_baseline_radio_button_unchecked)
                    ivIcon.setColorFilter(0xFFB0BEC5.toInt())
                    tvLabel.visibility = View.GONE
                    holder.itemView.setBackgroundResource(android.R.drawable.list_selector_background)
                }

                holder.itemView.setOnClickListener {
                    if (!isActive) {
                        GlobalScope.launch(Dispatchers.Main) {
                            withContext(Dispatchers.IO) {
                                ProfileProcessor.active(this@MainActivity, item.uuid)
                            }
                            design.fetch()
                            if (clashRunning) {
                                stopClashService()
                                design.startClash()
                            }
                        }
                    }
                }
            }
        }
    }
}
