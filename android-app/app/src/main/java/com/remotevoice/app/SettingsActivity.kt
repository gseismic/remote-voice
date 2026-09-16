package com.remotevoice.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 设置页（v3）：服务器地址（含默认值）、回声消除、免提常开、
 * 设备条目管理（查看/删除），以及 rv:// 一行配置串导入。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: DeviceStore
    private lateinit var serverEdit: EditText
    private lateinit var localEdit: EditText
    private lateinit var btnScan: Button
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
        localEdit = findViewById(R.id.edit_local)
        btnScan = findViewById(R.id.btn_scan)
        aecSwitch = findViewById(R.id.switch_aec)
        handsfreeSwitch = findViewById(R.id.switch_handsfree)
        devicesBox = findViewById(R.id.devices_box)
        val backBtn = findViewById<Button>(R.id.btn_back)

        // 空值时展示默认服务器，让用户看得见、可改
        serverEdit.setText(
            prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }
                ?: AudioStreamService.DEFAULT_SERVER
        )
        // 本地（局域网）服务器：扫描选择或手动填，可空
        localEdit.setText(prefs.getString(AudioStreamService.KEY_SERVER_LOCAL, "") ?: "")
        aecSwitch.isChecked = prefs.getBoolean(KEY_AEC, false)
        handsfreeSwitch.isChecked = prefs.getBoolean(AudioStreamService.KEY_HANDSFREE, false)

        parseBtn.setOnClickListener {
            val parsed = ConfigParser.parse(importEdit.text.toString())
            if (parsed == null) {
                Toast.makeText(this, R.string.toast_import_bad, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val server = ConfigParser.format(parsed)
            serverEdit.setText(server)
            prefs.edit().putString(KEY_SERVER, server).apply()
            Toast.makeText(this, R.string.toast_import_ok, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_add_device).setOnClickListener {
            showAddDeviceDialog()
        }

        // 局域网扫描（后台线程收包，回主线程弹结果）；扫不到时提示回落远程/手动
        btnScan.setOnClickListener {
            btnScan.isEnabled = false
            btnScan.text = "扫描中…"
            Thread {
                val found = try {
                    LanDiscovery.scan()
                } catch (_: Exception) {
                    emptyList()
                }
                runOnUiThread {
                    btnScan.isEnabled = true
                    btnScan.text = getString(R.string.btn_scan)
                    if (found.isEmpty()) {
                        Toast.makeText(this, R.string.toast_scan_none, Toast.LENGTH_LONG).show()
                    } else {
                        showScanResults(found)
                    }
                }
            }.start()
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

    /** 扫描结果列表：选中即保存为本地服务器地址并重连（本地优先，选不中仍走远程）。 */
    private fun showScanResults(found: List<LanDiscovery.Found>) {
        val titles = found.map { f ->
            (f.name.ifBlank { "server" }) + "\n${f.host}:${f.port}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择局域网服务器")
            .setItems(titles) { _, which ->
                val f = found[which]
                val addr = "${f.host}:${f.port}"
                localEdit.setText(addr)
                prefs.edit().putString(AudioStreamService.KEY_SERVER_LOCAL, addr).apply()
                Toast.makeText(this, "本地服务器已设为 $addr", Toast.LENGTH_SHORT).show()
                restartRunningService()
            }
            .setNegativeButton("取消", null)
            .show()
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
                        val removingActive = store.active()?.id == d.id
                        store.remove(d.id)
                        renderDevices()
                        if (removingActive) reconnectAfterDeviceRemoval()
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
                prefs.edit().putBoolean(AudioStreamService.KEY_USER_STOPPED, false).apply()
                renderDevices()
                saveAll(showToast = false)
                if (AudioStreamService.instance == null) finish()
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
                if (store.activeId() == d.id) {
                    restartRunningService()
                    if (AudioStreamService.instance == null) finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun typeOf(secret: String): String {
        val norm = secret.filter { it != ' ' && it != '-' }
        return if (norm.length >= 12) "perm" else "temp"
    }

    private fun saveAll(showToast: Boolean = true) {
        val server = serverEdit.text.toString().trim().ifBlank { AudioStreamService.DEFAULT_SERVER }
        prefs.edit()
            .putString(KEY_SERVER, server)
            .putString(AudioStreamService.KEY_SERVER_LOCAL, localEdit.text.toString().trim())
            .putBoolean(KEY_AEC, aecSwitch.isChecked)
            .putBoolean(AudioStreamService.KEY_HANDSFREE, handsfreeSwitch.isChecked)
            .apply()
        if (!prefs.getBoolean(AudioStreamService.KEY_USER_STOPPED, false)) {
            AudioStreamService.prepareRetry()
        }
        restartRunningService()
        if (showToast) Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    /** 配置页保存后让正在运行的服务读取最新服务器/设备配置。 */
    private fun restartRunningService() {
        if (prefs.getBoolean(AudioStreamService.KEY_USER_STOPPED, false)) return
        AudioStreamService.prepareRetry()
        if (AudioStreamService.instance == null) return
        startService(
            Intent(this, AudioStreamService::class.java)
                .setAction(AudioStreamService.ACTION_RESTART)
        )
    }

    /** 删除当前设备后切换到剩余设备；没有设备时停止前台服务。 */
    private fun reconnectAfterDeviceRemoval() {
        if (store.active() != null) {
            prefs.edit().putBoolean(AudioStreamService.KEY_USER_STOPPED, false).apply()
            restartRunningService()
            if (AudioStreamService.instance == null) finish()
            return
        }
        prefs.edit().putBoolean(AudioStreamService.KEY_USER_STOPPED, true).apply()
        if (AudioStreamService.instance != null) {
            startService(
                Intent(this, AudioStreamService::class.java)
                    .setAction(AudioStreamService.ACTION_STOP)
            )
        }
    }

    private companion object {
        const val PREFS = "config"
        const val KEY_SERVER = "server"
        const val KEY_AEC = "aec"
    }
}
