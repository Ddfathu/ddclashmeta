package com.github.kr328.clash

import android.app.Activity
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.ProxyLinkConverter
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.NewProfileDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ProfileProvider
import com.github.kr328.clash.remote.FilesClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.QRResult.QRError
import io.github.g00fy2.quickie.QRResult.QRMissingPermission
import io.github.g00fy2.quickie.QRResult.QRSuccess
import io.github.g00fy2.quickie.QRResult.QRUserCanceled
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NewProfileActivity : BaseActivity<NewProfileDesign>() {

    private val scanLauncher = registerForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRSuccess -> {
                val rawValue = result.content.rawValue
                if (!rawValue.isNullOrBlank()) {
                    handleScannedLink(rawValue)
                }
            }
            is QRMissingPermission -> {
                Toast.makeText(this, "Izin kamera dibutuhkan", Toast.LENGTH_SHORT).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            is QRError -> Toast.makeText(this, "Error kamera: ${result.exception.message}", Toast.LENGTH_SHORT).show()
            QRUserCanceled -> {}
        }
    }

    override suspend fun main() {
        val design = NewProfileDesign(this)
        setContentDesign(design)

        design.patchProviders(queryProfileProviders())

        while (isActive) {
            select<Unit> {
                events.onReceive {}
                design.requests.onReceive {
                    when (it) {
                        is NewProfileDesign.Request.Create -> {
                            when (it.provider) {
                                is ProfileProvider.ClipboardNew -> {
                                    handleClipboardStandalone()
                                }
                                is ProfileProvider.ClipboardAppend -> {
                                    handleClipboardProviderAppend()
                                }
                                else -> {
                                    withProfile {
                                        val name = getString(R.string.new_profile)
                                        val uuid: UUID? = when (val p = it.provider) {
                                            is ProfileProvider.File -> create(Profile.Type.File, name)
                                            is ProfileProvider.Url -> create(Profile.Type.Url, name)
                                            is ProfileProvider.External -> {
                                                val data = p.get()
                                                if (data != null) {
                                                    val (uri, initialName) = data
                                                    create(Profile.Type.External, initialName ?: name, uri.toString())
                                                } else null
                                            }
                                            else -> null
                                        }
                                        if (uuid != null) launchProperties(uuid)
                                    }
                                }
                            }
                        }
                        is NewProfileDesign.Request.OpenDetail -> launchAppDetailed(it.provider)
                        is NewProfileDesign.Request.LaunchScanner -> scanLauncher.launch(null)
                    }
                }
            }
        }
    }

    private fun getClipboardText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipData = cm?.primaryClip
        return if (clipData != null && clipData.itemCount > 0) {
            clipData.getItemAt(0)?.text?.toString().orEmpty()
        } else {
            ""
        }
    }

    // Opsi 1: Profil Baru Mandiri
    private suspend fun handleClipboardStandalone() {
        val text = getClipboardText()
        if (text.isBlank() || !ProxyLinkConverter.isProxyLink(text)) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@NewProfileActivity, "Clipboard tidak berisi link vless/vmess/trojan", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val (yamlContent, nodeName) = ProxyLinkConverter.generateStandaloneYaml(text)
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val profileName = "$nodeName ($time)"

            withProfile {
                val profileId = create(Profile.Type.File, profileName)
                val client = FilesClient(this@NewProfileActivity)

                withContext(Dispatchers.IO) {
                    val targetUri = client.buildDocumentUri("$profileId/config.yaml")
                    val outputStream = contentResolver.openOutputStream(targetUri, "rwt")
                        ?: throw IllegalStateException("Gagal membuka file config.yaml")
                    OutputStreamWriter(outputStream).use { it.write(yamlContent) }
                }

                commit(profileId)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NewProfileActivity, "Profil '$profileName' berhasil dibuat!", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@NewProfileActivity, "Gagal import standalone: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Opsi 2: Kentang Mode (Satu profil, selalu di-commit agar permanen)
    private suspend fun handleClipboardProviderAppend() {
        val text = getClipboardText()
        if (text.isBlank() || !ProxyLinkConverter.isProxyLink(text)) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@NewProfileActivity, "Clipboard tidak berisi link vless/vmess/trojan", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val targetName = "Kentang Profile"
            val client = FilesClient(this@NewProfileActivity)

            withProfile {
                val existing = queryAll().firstOrNull { it.name == targetName }
                val profileId = existing?.uuid ?: create(Profile.Type.File, targetName)

                withContext(Dispatchers.IO) {
                    val targetUri = client.buildDocumentUri("$profileId/config.yaml")
                    var oldYaml = ""
                    try {
                        contentResolver.openInputStream(targetUri)?.use { stream ->
                            oldYaml = BufferedReader(InputStreamReader(stream)).readText()
                        }
                    } catch (e: Exception) {
                        oldYaml = ""
                    }

                    val updatedYaml = ProxyLinkConverter.appendOrGenerateDirect(oldYaml, text)

                    val outputStream = contentResolver.openOutputStream(targetUri, "rwt")
                        ?: throw IllegalStateException("Gagal membuka file config.yaml")
                    OutputStreamWriter(outputStream).use { it.write(updatedYaml) }
                }

                // Wajib dipanggil setiap kali ada penambahan node agar database menyinkronkan snapshot terbaru
                commit(profileId)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NewProfileActivity, "Node berhasil disuntik ke Kentang Profile!", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@NewProfileActivity, "Gagal append kentang: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleScannedLink(content: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = android.content.ClipData.newPlainText("Scanned Proxy", content)
        cm?.setPrimaryClip(clip)
        lifecycleScope.launch {
            handleClipboardStandalone()
        }
    }

    private fun launchAppDetailed(provider: ProfileProvider.External) {
        val data = Uri.fromParts("package", provider.intent.component?.packageName ?: return, null)
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, data))
    }

    private fun launchProperties(uuid: UUID) {
        startActivity(PropertiesActivity::class.intent.setUUID(uuid))
        finish()
    }

    private suspend fun ProfileProvider.External.get(): Pair<Uri, String?>? {
        val intent = Intent(intent).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intents.EXTRA_NAME, getString(R.string.new_profile))
        }
        val result = startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            intent
        )
        if (result.resultCode != Activity.RESULT_OK) return null
        return (result.data?.data ?: return null) to
                result.data?.getStringExtra(Intents.EXTRA_NAME)
    }

    private suspend fun queryProfileProviders(): List<ProfileProvider> {
        return listOf(
            ProfileProvider.File(this),
            ProfileProvider.Url(this),
            ProfileProvider.QR(this),
            ProfileProvider.ClipboardNew(this),
            ProfileProvider.ClipboardAppend(this)
        )
    }
}
