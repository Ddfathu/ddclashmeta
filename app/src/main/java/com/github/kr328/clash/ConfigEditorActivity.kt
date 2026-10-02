package com.github.kr328.clash

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.common.util.getUUID
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.design.R as DesignR
import kotlinx.coroutines.*
import java.io.File

class ConfigEditorActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private lateinit var etConfig: EditText
    private lateinit var btnSave: Button
    private var configFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(DesignR.layout.activity_config_editor)

        etConfig = findViewById(DesignR.id.et_config)
        btnSave = findViewById(DesignR.id.btn_save)

        val uuid = intent.getUUID()
        if (uuid == null) {
            Toast.makeText(this, "UUID Profil tidak valid", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val targetDir = this.importedDir.resolve(uuid.toString())
        configFile = targetDir.resolve("config.yaml")

        launch(Dispatchers.IO) {
            val content = try {
                if (configFile?.exists() == true) {
                    configFile?.readText() ?: ""
                } else {
                    ""
                }
            } catch (e: Exception) {
                ""
            }
            withContext(Dispatchers.Main) {
                etConfig.setText(content)
            }
        }

        btnSave.setOnClickListener {
            val newText = etConfig.text.toString()
            launch(Dispatchers.IO) {
                try {
                    configFile?.writeText(newText)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ConfigEditorActivity, "Konfigurasi berhasil disimpan!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ConfigEditorActivity, "Gagal menyimpan: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }
}
