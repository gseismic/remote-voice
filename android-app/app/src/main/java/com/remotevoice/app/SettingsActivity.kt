package com.remotevoice.app

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 设置页（v2）：服务器地址（含默认值）、服务端指纹、回声消除、免提常开、
 * 设备条目管理（查看/删除），以及 rv:// 一行配置串导入（仅服务器与指纹）。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: DeviceStore
    private lateinit var serverEdit: EditText
    private lateinit var fpState: TextView
    private lateinit var aecSwitch: Switch
    private lateinit var handsfreeSwitch: Switch
    private lateinit var devicesBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.title_settings)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        store = DeviceStore(this)
        setContentView(R.layout.activity_settings)

        val importEdit = findViewById<EditText>(R.id.edit_import)
        val parseBtn = findViewById<Button>(R.id.btn_parse)
        serverEdit = findViewById(R.id.edit_server)
        fpState = findViewById(R.id.text_fp_state)
        aecSwitch = findViewById(R.id.switch_aec)
        handsfreeSwitch = findViewById(R.id.switch_handsfree)
        devicesBox = findViewById(R.id.devices_box)
        val backBtn = findViewById<Button>(R.id.btn_back)

        // 空值时展示默认服务器，让用户看得见、可改
        serverEdit.setText(
            prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }
                ?: AudioStreamService.DEFAULT_SERVER
        )
        renderFpState()
        aecSwitch.isChecked = prefs.getBoolean(KEY_AEC, false)
        handsfreeSwitch.isChecked = prefs.getBoolean(AudioStreamService.KEY_HANDSFREE, false)

        findViewById<Button>(R.id.btn_clear_fp).setOnClickListener {
            prefs.edit().remove(KEY_FINGERPRINT).apply()
            renderFpState()
            Toast.makeText(this, R.string.toast_fp_cleared, Toast.LENGTH_SHORT).show()
        }

        parseBtn.setOnClickListener {
            val parsed = ConfigParser.parse(importEdit.text.toString())
            if (parsed == null) {
                Toast.makeText(this, R.string.toast_import_bad, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val server = "${parsed.host}:${parsed.port}"
            serverEdit.setText(server)
            prefs.edit()
                .putString(KEY_SERVER, server)
                .apply {
                    if (parsed.fingerprint.isNotEmpty()) {
                        putString(KEY_FINGERPRINT, parsed.fingerprint)
                    }
                }.apply()
            renderFpState()
            Toast.makeText(this, R.string.toast_import_ok, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_add_device).setOnClickListener {
            showAddDeviceDialog()
        }

        backBtn.setOnClickListener {
            saveAll()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        renderDevices()
    }

    private fun renderDevices() {
        devicesBox.removeAllViews()
        val devices = store.list()
        for (d in devices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
            }
            val name = TextView(this).apply {
                text = d.name.ifBlank { d.secret.take(12) + "…" }
                textSize = 14f
                setTextColor(0xFFE6E9EE.toInt())
            }
            val meta = TextView(this).apply {
                text = (if (d.type == "perm") "永久秘密" else "临时秘密") +
                    " · 秘密已保存本机 · 长按可修改"
                textSize = 11f
                setTextColor(0xFF8B949E.toInt())
            }
            row.addView(name)
            row.addView(meta)
            row.setOnLongClickListener {
                showDeviceMenu(d)
                true
            }
            devicesBox.addView(row)
        }
        if (devices.isEmpty()) {
            val empty = TextView(this).apply {
                text = "尚未添加设备：先在 Mac 端确认秘密，再点下方「添加设备」"
                textSize = 12f
                setTextColor(0xFF8B949E.toInt())
            }
            devicesBox.addView(empty)
        }
    }

    private fun showDeviceMenu(d: DeviceStore.Device) {
        val menu = arrayOf("修改名字与秘密", "删除设备")
        AlertDialog.Builder(this)
            .setTitle(d.name.ifBlank { "设备" })
            .setItems(menu) { _, which ->
                when (which) {
                    0 -> showEditDeviceDialog(d)
                    1 -> {
                        store.remove(d.id)
                        renderDevices()
                    }
                }
            }
            .show()
    }

    private fun showAddDeviceDialog() {
        val alias = EditText(this).apply {
            hint = "设备名（选填，首次连接后自动补全）"
            setSingleLine(true)
        }
        val secret = EditText(this).apply {
            hint = "Mac 端显示的临时/永久秘密"
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(alias)
            addView(secret)
        }
        AlertDialog.Builder(this)
            .setTitle("添加设备")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val s = secret.text.toString().trim()
                if (s.isEmpty()) {
                    Toast.makeText(this, "秘密不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.add(alias.text.toString().trim(), s, typeOf(s))
                renderDevices()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditDeviceDialog(d: DeviceStore.Device) {
        val alias = EditText(this).apply {
            setText(d.name)
            hint = "设备名"
            setSingleLine(true)
        }
        val secret = EditText(this).apply {
            setText(d.secret)
            hint = "秘密"
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(alias)
            addView(secret)
        }
        AlertDialog.Builder(this)
            .setTitle("修改设备")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val s = secret.text.toString().trim()
                if (s.isEmpty()) {
                    Toast.makeText(this, "秘密不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.update(d.id, alias.text.toString().trim(), s, typeOf(s))
                renderDevices()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun typeOf(secret: String): String {
        val norm = secret.filter { it != ' ' && it != '-' }
        return if (norm.length >= 12) "perm" else "temp"
    }

    /** 服务器信任展示：已信任=显示指纹前缀；未信任=提示首次连接自动记录。 */
    private fun renderFpState() {
        val fp = prefs.getString(KEY_FINGERPRINT, "") ?: ""
        if (fp.length == 64) {
            fpState.text = getString(R.string.fp_trusted_prefix, fp.take(12))
            fpState.setTextColor(0xFF3FB950.toInt())
        } else {
            fpState.text = getString(R.string.fp_trust_empty)
            fpState.setTextColor(0xFF8B949E.toInt())
        }
    }

    private fun saveAll() {
        val server = serverEdit.text.toString().trim().ifBlank { AudioStreamService.DEFAULT_SERVER }
        prefs.edit()
            .putString(KEY_SERVER, server)
            .putBoolean(KEY_AEC, aecSwitch.isChecked)
            .putBoolean(AudioStreamService.KEY_HANDSFREE, handsfreeSwitch.isChecked)
            .apply()
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS = "config"
        const val KEY_SERVER = "server"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_AEC = "aec"
    }
}
