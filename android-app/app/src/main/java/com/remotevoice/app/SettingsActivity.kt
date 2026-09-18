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
 * 设置页（V3.2 原型 docs/ui-design/v3/android.html）：
 * SERVER 单一服务器地址（IP:端口 或 rv://，本地测试填局域网 IP）、
 * DEVICES 设备条目管理（添加/编辑/删除，秘密保存本机）、
 * ADVANCED 回声消除/清除服务器信任（免提常开已随 V3.2 纯 PTT 语义移除）。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: DeviceStore
    private lateinit var serverEdit: EditText
    private lateinit var aecSwitch: Switch
    private lateinit var devicesBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.title_settings)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        store = DeviceStore(this)
        setContentView(R.layout.activity_settings)

        serverEdit = findViewById(R.id.edit_server)
        aecSwitch = findViewById(R.id.switch_aec)
        devicesBox = findViewById(R.id.devices_box)

        // 空值时展示默认服务器，让用户看得见、可改
        serverEdit.setText(
            prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }
                ?: AudioStreamService.DEFAULT_SERVER
        )
        aecSwitch.isChecked = prefs.getBoolean(KEY_AEC, false)

        findViewById<Button>(R.id.btn_add_device).setOnClickListener {
            showAddDeviceDialog()
        }

        // 清除当前输入服务器的 TOFU 指纹（证书更换等场景），确认后执行
        findViewById<Button>(R.id.btn_clear_trust).setOnClickListener {
            val target = currentServerParsed()
            if (target == null) {
                Toast.makeText(this, R.string.toast_addr_bad, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val addr = ConfigParser.format(target)
            AlertDialog.Builder(this)
                .setTitle(R.string.label_trust)
                .setMessage("清除 $addr 的服务器指纹后，首次连接将重新接受其证书。继续吗？")
                .setPositiveButton(R.string.btn_trust_clear) { _, _ ->
                    TrustStore.clear(prefs, TrustStore.serverKey(target.host, target.port))
                    Toast.makeText(this, R.string.toast_trust_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<Button>(R.id.btn_back).setOnClickListener {
            saveAll()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        renderDevices()
    }

    /** 当前输入框地址归一化；空值回落默认服务器，格式无效返回 null。 */
    private fun currentServerParsed() =
        ConfigParser.parse(serverEdit.text.toString().trim().ifBlank { AudioStreamService.DEFAULT_SERVER })

    private fun renderDevices() {
        devicesBox.removeAllViews()
        val devices = store.list()
        for (d in devices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
            }
            val name = TextView(this).apply {
                text = (if (store.active()?.id == d.id) "● " else "○ ") +
                    d.name.ifBlank { d.secret.take(12) + "…" }
                textSize = 13f
                setTextColor(0xFFE6E9EE.toInt())
            }
            val meta = TextView(this).apply {
                text = (if (d.type == "perm") "永久密码" else "临时密码") +
                    " · 密码已保存本机 · 长按可修改"
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
                text = "尚未添加设备：先在 Mac 端确认密码，再点下方「添加设备」"
                textSize = 12f
                setTextColor(0xFF8B949E.toInt())
            }
            devicesBox.addView(empty)
        }
    }

    private fun showDeviceMenu(d: DeviceStore.Device) {
        val menu = arrayOf("修改名字与密码", "删除设备")
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
            hint = "Mac 端显示的临时/永久密码"
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
                    Toast.makeText(this, "密码不能为空", Toast.LENGTH_SHORT).show()
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
            hint = "密码"
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
                    Toast.makeText(this, "密码不能为空", Toast.LENGTH_SHORT).show()
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
        val raw = serverEdit.text.toString().trim()
        val server = if (raw.isEmpty()) {
            AudioStreamService.DEFAULT_SERVER
        } else {
            // rv:// 或 host:port 统一归一化（IPv6 加括号、默认端口补全）
            val parsed = ConfigParser.parse(raw)
            if (parsed == null) {
                Toast.makeText(this, R.string.toast_addr_bad, Toast.LENGTH_LONG).show()
                return
            }
            ConfigParser.format(parsed)
        }
        prefs.edit()
            .putString(KEY_SERVER, server)
            .putBoolean(KEY_AEC, aecSwitch.isChecked)
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
