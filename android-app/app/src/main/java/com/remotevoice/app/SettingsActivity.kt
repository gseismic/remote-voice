package com.remotevoice.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast

/**
 * 设置页（协议 v4）：SERVER 服务器地址 + 服务器密码（ServerStore 单一事实源）、
 * ADVANCED 回声消除/清除服务器信任。
 * Mac 列表来自服务器目录（主界面渲染），本页不再管理设备条目。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var serverEdit: EditText
    private lateinit var passwordEdit: EditText
    private lateinit var aecSwitch: Switch
    private lateinit var autoReconnectSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.title_settings)
        prefs = getSharedPreferences(AudioStreamService.PREFS, MODE_PRIVATE)
        setContentView(R.layout.activity_settings)

        serverEdit = findViewById(R.id.edit_server)
        passwordEdit = findViewById(R.id.edit_password)
        aecSwitch = findViewById(R.id.switch_aec)
        autoReconnectSwitch = findViewById(R.id.switch_auto_reconnect)

        // 空值时展示默认服务器，让用户看得见、可改
        val active = ServerStore(this).active()
        serverEdit.setText(
            active?.let { "${it.host}:${it.port}" }
                ?: prefs.getString("server", null)?.takeIf { it.isNotBlank() }
                ?: AudioStreamService.DEFAULT_SERVER
        )
        // 密码不回显明文（占位提示已保存）；留空=不修改
        if (!active?.password.isNullOrBlank()) {
            passwordEdit.hint = "已保存（输入新值即更新）"
        }
        aecSwitch.isChecked = prefs.getBoolean(KEY_AEC, false)
        autoReconnectSwitch.isChecked = prefs.getBoolean(AudioStreamService.KEY_AUTO_RECONNECT, true)

        // 清除当前输入服务器的证书记录（证书更换等场景），确认后执行
        findViewById<Button>(R.id.btn_clear_trust).setOnClickListener {
            val target = currentServerParsed()
            if (target == null) {
                Toast.makeText(this, R.string.toast_addr_bad, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val addr =
                if (target.first.contains(':')) "[${target.first}]:${target.second}"
                else "${target.first}:${target.second}"
            AlertDialog.Builder(this)
                .setTitle(R.string.label_trust)
                .setMessage("清除 $addr 的本机证书记录后，下次连接会重新信任其证书。继续吗？")
                .setPositiveButton(R.string.btn_trust_clear) { _, _ ->
                    TrustStore.clear(prefs, TrustStore.serverKey(target.first, target.second))
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

    /** 当前输入框地址归一化；空值回落默认服务器，格式无效返回 null。 */
    private fun currentServerParsed(): Pair<String, Int>? =
        ConfigParser.parse(
            serverEdit.text.toString().trim().ifBlank { AudioStreamService.DEFAULT_SERVER }
        )?.let { it.host to it.port }

    private fun saveAll(showToast: Boolean = true) {
        val raw = serverEdit.text.toString().trim()
        val parsed = ConfigParser.parse(raw.ifBlank { AudioStreamService.DEFAULT_SERVER })
        if (parsed == null) {
            Toast.makeText(this, R.string.toast_addr_bad, Toast.LENGTH_LONG).show()
            return
        }
        // 归一化地址（IPv6 加括号、默认端口补全）并更新服务器条目；密码留空=不变
        val password = passwordEdit.text.toString().trim()
        val store = ServerStore(this)
        val active = store.active()
        val effectivePassword = password.ifBlank { active?.password.orEmpty() }
        store.add(parsed.host, parsed.port, effectivePassword)
        if (!prefs.getBoolean(AudioStreamService.KEY_USER_STOPPED, false)) {
            AudioStreamService.prepareRetry()
        }
        restartRunningService()
        if (showToast) Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    private fun restartRunningService() {
        if (AudioStreamService.instance != null) {
            startService(
                Intent(this, AudioStreamService::class.java)
                    .setAction(AudioStreamService.ACTION_RESTART)
            )
        }
    }

    private companion object {
        private const val KEY_AEC = "aec"
    }
}
