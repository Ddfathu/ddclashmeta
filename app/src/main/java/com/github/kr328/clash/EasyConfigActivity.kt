package com.github.kr328.clash

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.service.ProfileProcessor
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.EasyConfigManager
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class EasyConfigActivity : AppCompatActivity() {

    private lateinit var editText: EditText
    private val scope = CoroutineScope(Dispatchers.Main)
    private val profileName = "Config Easy"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(24, 24, 24, 24)
        }

        // Header Bar
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 24)
        }

        val title = TextView(this).apply {
            text = "Config Editor"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnAdd = Button(this).apply {
            text = "+"
            textSize = 18f
            setTextColor(0xFF00E676.toInt())
            setBackgroundColor(0x3300E676.toInt())
            setOnClickListener {
                importFromClipboard()
            }
        }

        val btnClear = Button(this).apply {
            text = "Clear"
            setTextColor(0xFFFF5252.toInt())
            setBackgroundColor(0x33FF5252.toInt())
            setOnClickListener {
                editText.setText("")
            }
        }

        val btnSave = Button(this).apply {
            text = "Simpan"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF7C4DFF.toInt())
            setOnClickListener {
                saveConfig()
            }
        }

        headerLayout.addView(title)
        headerLayout.addView(btnAdd)
        headerLayout.addView(btnClear)
        headerLayout.addView(btnSave)
        rootLayout.addView(headerLayout)

        // Text Editor
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        editText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(0xFFE0E0E0.toInt())
            hint = "# Klik tombol (+) di atas untuk import link VLESS/VMess/Trojan dari clipboard\natau ketik/paste manual blok YAML di sini."
            setHintTextColor(0xFF757575.toInt())
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(24, 24, 24, 24)
            isVerticalScrollBarEnabled = true
            inputType = InputType.TYPE_CLASS_TEXT or 
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or 
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        scrollView.addView(editText)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        // loadExistingConfig() -> Editor siap membuat profil mandiri
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData == null || clipData.itemCount == 0) {
            Toast.makeText(this, "Clipboard kosong!", Toast.LENGTH_SHORT).show()
            return
        }

        val text = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            Toast.makeText(this, "Clipboard kosong!", Toast.LENGTH_SHORT).show()
            return
        }

        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val parsedNodes = mutableListOf<String>()

        for (line in lines) {
            val nodeYaml = EasyConfigManager.parseLinkToNodeYaml(line)
            if (nodeYaml != null) {
                parsedNodes.add(nodeYaml)
            }
        }

        if (parsedNodes.isNotEmpty()) {
            val combined = parsedNodes.joinToString("\n\n")
            val currentText = editText.text.toString().trim()
            if (currentText.isEmpty()) {
                editText.setText(combined)
            } else {
                editText.setText("$currentText\n\n$combined")
            }
            Toast.makeText(this, "Berhasil menambahkan ${parsedNodes.size} node!", Toast.LENGTH_SHORT).show()
        } else if (text.startsWith("- name:") || text.contains("server:")) {
            val currentText = editText.text.toString().trim()
            if (currentText.isEmpty()) {
                editText.setText(text)
            } else {
                editText.setText("$currentText\n\n$text")
            }
            Toast.makeText(this, "Berhasil menempelkan YAML node!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Format link tidak didukung!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun // loadExistingConfig() -> Editor siap membuat profil mandiri {
        scope.launch(Dispatchers.IO) {
            val dao = ImportedDao()
            val existing = dao.queryAllUUIDs()
                .mapNotNull { dao.queryByUUID(it) }
                .firstOrNull { it.name == profileName }

            if (existing != null) {
                val file = this@EasyConfigActivity.importedDir.resolve(existing.uuid.toString()).resolve("config.yaml")
                if (file.exists()) {
                    val full = file.readText()
                    val rawNodes = EasyConfigManager.extractRawNodes(full)
                    withContext(Dispatchers.Main) {
                        editText.setText(rawNodes)
                    }
                }
            }
        }
    }

    private fun saveConfig() {
        val rawText = editText.text.toString().trim()
        if (rawText.isEmpty()) {
            Toast.makeText(this, "Node / Config tidak boleh kosong!", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                // Ambil nama node jika ada, atau buat nama profil unik berdasarkan waktu
                val proxyNames = EasyConfigManager.extractProxyNames(rawText)
                val targetName = proxyNames.firstOrNull() ?: "Config-${System.currentTimeMillis() % 10000}"

                val dao = ImportedDao()
                val targetUuid = UUID.randomUUID()

                val profileDir = this@EasyConfigActivity.importedDir.resolve(targetUuid.toString())
                if (!profileDir.exists()) profileDir.mkdirs()

                // Jika user paste link mentah/node mentah, bungkus jadi full config.
                // Jika sudah full config (ada mixed-port), gunakan langsung.
                val fullConfig = if (rawText.contains("mixed-port:") || rawText.contains("port:")) {
                    rawText
                } else {
                    EasyConfigManager.buildFullConfig(rawText)
                }

                val targetFile = profileDir.resolve("config.yaml")
                targetFile.writeText(fullConfig, Charsets.UTF_8)

                val newProfile = Imported(
                    uuid = targetUuid,
                    name = targetName,
                    type = Profile.Type.File,
                    source = targetFile.absolutePath,
                    interval = 0,
                    upload = 0,
                    download = 0,
                    total = 0,
                    expire = 0,
                    createdAt = System.currentTimeMillis()
                )
                dao.insert(newProfile)

                ProfileProcessor.active(this@EasyConfigActivity, targetUuid)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EasyConfigActivity, "Profil \"$targetName\" berhasil dibuat & aktif!", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EasyConfigActivity, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}