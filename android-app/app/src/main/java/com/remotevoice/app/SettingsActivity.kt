package com.remotevoice.app

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast

/**
 * 设置页：服务器地址（含默认值）、服务端指纹、回声消除，
 * 以及 rv:// 一行配置串导入。保存写入与主界面同一份 prefs。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var serverEdit: EditText
    private lateinit var fpEdit: EditText
    private lateinit var aecSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.title_settings)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContentView(R.layout.activity_settings)

        val importEdit = findViewById<EditText>(R.id.edit_import)
        val parseBtn = findViewById<Button>(R.id.btn_parse)
        serverEdit = findViewById(R.id.edit_server)
        fpEdit = findViewById(R.id.edit_fingerprint)
        aecSwitch = findViewById(R.id.switch_aec)
        val backBtn = findViewById<Button>(R.id.btn_back)

        // 空值时展示默认服务器，让用户看得见、可改
        serverEdit.setText(
            prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }
                ?: AudioStreamService.DEFAULT_SERVER
        )
        fpEdit.setText(prefs.getString(KEY_FINGERPRINT, ""))
        aecSwitch.isChecked = prefs.getBoolean(KEY_AEC, false)

        parseBtn.setOnClickListener {
            val parsed = ConfigParser.parse(importEdit.text.toString())
            if (parsed == null) {
                Toast.makeText(this, R.string.toast_import_bad, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val server = "${parsed.host}:${parsed.port}"
            serverEdit.setText(server)
            fpEdit.setText(parsed.fingerprint)
            // 密码同步写回，主界面输入框在 onResume 后的读取中体现
            prefs.edit()
                .putString(KEY_TOKEN, parsed.password)
                .putString(KEY_SERVER, server)
                .putString(KEY_FINGERPRINT, parsed.fingerprint)
                .apply()
            Toast.makeText(this, R.string.toast_import_ok, Toast.LENGTH_SHORT).show()
        }

        backBtn.setOnClickListener {
            saveAll()
            finish()
        }
    }

    private fun saveAll() {
        val server = serverEdit.text.toString().trim().ifBlank { AudioStreamService.DEFAULT_SERVER }
        val fp = ConfigParser.normalizeFingerprint(fpEdit.text.toString())
        prefs.edit()
            .putString(KEY_SERVER, server)
            .putString(KEY_FINGERPRINT, fp)
            .putBoolean(KEY_AEC, aecSwitch.isChecked)
            .apply()
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS = "config"
        const val KEY_TOKEN = "token"
        const val KEY_SERVER = "server"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_AEC = "aec"
    }
}
