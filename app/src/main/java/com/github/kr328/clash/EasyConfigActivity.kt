package com.github.kr328.clash

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
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
import java.io.File
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
            text = "Config Easy Editor"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
        headerLayout.addView(btnClear)
        headerLayout.addView(btnSave)
        rootLayout.addView(headerLayout)

        // Editor Input (Monospace font, tanpa autocorrect perusak YAML)
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
            hint = "# Tempel daftar node Anda di sini:\n- name: \"Node 1\"\n  server: ...\n- name: \"Node 2\"\n  server: ..."
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

        loadExistingConfig()
    }

    private fun loadExistingConfig() {
        scope.launch(Dispatchers.IO) {
            val dao = ImportedDao()
            val existing = dao.queryAll().firstOrNull { it.name == profileName }
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
            Toast.makeText(this, "Node tidak boleh kosong!", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val dao = ImportedDao()
                var target = dao.queryAll().firstOrNull { it.name == profileName }
                val targetUuid = target?.uuid ?: UUID.randomUUID()

                val profileDir = this@EasyConfigActivity.importedDir.resolve(targetUuid.toString())
                if (!profileDir.exists()) profileDir.mkdirs()

                val fullConfig = EasyConfigManager.buildFullConfig(rawText)
                val targetFile = profileDir.resolve("config.yaml")
                targetFile.writeText(fullConfig, Charsets.UTF_8)

                if (target == null) {
                    target = Imported(
                        uuid = targetUuid,
                        name = profileName,
                        type = Profile.Type.File,
                        source = targetFile.absolutePath,
                        interval = 0,
                        upload = 0,
                        download = 0,
                        total = 0,
                        expire = 0,
                        createdAt = System.currentTimeMillis()
                    )
                    dao.insert(target)
                }

                ProfileProcessor.active(this@EasyConfigActivity, targetUuid)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EasyConfigActivity, "Config Easy berhasil disimpan & diaktifkan!", Toast.LENGTH_SHORT).show()
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
