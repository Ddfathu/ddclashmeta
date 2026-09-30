package com.github.kr328.clash

import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.common.util.ProxyLinkConverter
import com.github.kr328.clash.design.NewProfileDesign
import com.github.kr328.clash.service.data.Profile
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.service.remote.FilesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

class NewProfileActivity : BaseActivity<NewProfileDesign>() {
    override suspend fun main() {
        val design = NewProfileDesign(this)

        setContentDesign(design)

        while (true) {
            when (design.requests.receive()) {
                NewProfileDesign.Request.CreateFile -> {
                    startActivity(CreateProfileActivity::class.java)
                    finish()
                    return
                }
                NewProfileDesign.Request.CreateUrl -> {
                    startActivity(CreateProfileActivity::class.java) {
                        putExtra(CreateProfileActivity.EXTRA_TYPE, Profile.Type.Url.name)
                    }
                    finish()
                    return
                }
                NewProfileDesign.Request.CreateExternal -> {
                    startActivity(CreateProfileActivity::class.java) {
                        putExtra(CreateProfileActivity.EXTRA_TYPE, Profile.Type.External.name)
                    }
                    finish()
                    return
                }
                NewProfileDesign.Request.ImportClipboard -> {
                    handleImportClipboard()
                }
            }
        }
    }

    private suspend fun handleImportClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val clipData = clipboard?.primaryClip
        val text = if (clipData != null && clipData.itemCount > 0) {
            clipData.getItemAt(0)?.text?.toString().orEmpty()
        } else {
            ""
        }

        if (text.isBlank() || !ProxyLinkConverter.isProxyLink(text)) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@NewProfileActivity, "Clipboard kosong atau tidak ada link vless/vmess/trojan", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            withProfile {
                val targetName = "Quick / Clipboard"
                val existingList = queryAll()
                val existing = existingList.firstOrNull { it.name == targetName }
                val profileId = existing?.uuid ?: create(Profile.Type.File, targetName)
                val client = FilesClient(this@NewProfileActivity)

                withContext(Dispatchers.IO) {
                    val targetUri = client.buildDocumentUri("$profileId/config.yaml")
                    val existingText = try {
                        contentResolver.openInputStream(targetUri)?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }

                    val yamlContent = ProxyLinkConverter.appendOrGenerate(existingText, text)

                    val outputStream = contentResolver.openOutputStream(targetUri, "rwt")
                        ?: throw IllegalStateException("Gagal membuka stream berkas profil")

                    OutputStreamWriter(outputStream).use { writer ->
                        writer.write(yamlContent)
                    }
                }

                // Simpan profile ke database dan close activity
                commit(profileId)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NewProfileActivity, "Node proxy berhasil ditambahkan ke profil!", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@NewProfileActivity, "Gagal mengonversi link: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
