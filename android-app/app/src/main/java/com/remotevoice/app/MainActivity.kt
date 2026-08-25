package com.remotevoice.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.security.SecureRandom

/**
 * 主界面（极简）：连接密码输入/生成 + 启停控制 + 着色状态。
 * 服务器地址与指纹等一次性配置收在 [SettingsActivity]；
 * 密码即三端共享的 token 原文（不做透明派生，保证所见即所得）。
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var pwdEdit: EditText
    private val pollHandler = Handler(Looper.getMainLooper())

    private val pollTask = object : Runnable {
        override fun run() {
            val st = AudioStreamService.Status
            statusView.text = if (st.state == StatusState.STREAMING) {
                "${st.text}\n已发送 ${st.framesSent} 帧"
            } else {
                st.text
            }
            statusView.setTextColor(
                when (st.state) {
                    StatusState.STREAMING -> COLOR_GREEN
                    StatusState.CONNECTING,
                    StatusState.WAIT_PEER -> COLOR_BLUE
                    StatusState.ERROR -> COLOR_RED
                    else -> COLOR_GRAY
                }
            )
            // 运行期间禁止重复启动；停止/出错态允许再次开始
            startBtn.isEnabled = st.state == StatusState.STOPPED ||
                st.state == StatusState.ERROR
            pollHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContentView(R.layout.activity_main)

        pwdEdit = findViewById(R.id.edit_password)
        statusView = findViewById(R.id.text_status)
        startBtn = findViewById(R.id.btn_start)
        val stopBtn = findViewById<Button>(R.id.btn_stop)
        val genBtn = findViewById<Button>(R.id.btn_gen)

        pwdEdit.setText(prefs.getString(KEY_TOKEN, ""))

        genBtn.setOnClickListener {
            pwdEdit.setText(generatePassword())
            Toast.makeText(this, R.string.toast_generated, Toast.LENGTH_SHORT).show()
        }
        startBtn.setOnClickListener {
            val password = pwdEdit.text.toString().trim()
            if (password.isEmpty()) {
                Toast.makeText(this, R.string.err_no_password, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val server = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }
                ?: AudioStreamService.DEFAULT_SERVER
            val fp = ConfigParser.normalizeFingerprint(
                prefs.getString(KEY_FINGERPRINT, "") ?: ""
            )
            if (fp.length != 64) {
                Toast.makeText(this, R.string.err_no_fingerprint, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            prefs.edit()
                .putString(KEY_TOKEN, password)
                .putString(KEY_SERVER, server)
                .putString(KEY_FINGERPRINT, fp)
                .apply()
            requestPermissionsThenStart()
        }
        stopBtn.setOnClickListener {
            startService(
                Intent(this, AudioStreamService::class.java)
                    .setAction(AudioStreamService.ACTION_STOP)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        pollHandler.post(pollTask)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollTask)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    /** 高熵随机密码：剔除易混淆字符（0O1lI）的 base58 风格字母表，163bit 强度。 */
    private fun generatePassword(): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz"
        val rnd = SecureRandom()
        val sb = StringBuilder(28)
        repeat(28) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    private fun requestPermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val denied = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            doStartService()
        } else {
            requestPermissions(denied.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            doStartService()
        } else {
            Toast.makeText(this, R.string.err_no_mic_perm, Toast.LENGTH_LONG).show()
        }
    }

    private fun doStartService() {
        // 服务内部有 clientThread 存活检查，重复点击安全
        startForegroundService(Intent(this, AudioStreamService::class.java))
        Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS = "config"
        const val KEY_TOKEN = "token"
        const val KEY_SERVER = "server"
        const val KEY_FINGERPRINT = "fingerprint"
        const val REQ_PERMS = 1

        val COLOR_GREEN = 0xFF2E7D32.toInt()
        val COLOR_BLUE = 0xFF1565C0.toInt()
        val COLOR_RED = 0xFFC62828.toInt()
        val COLOR_GRAY = 0xFF8A8A8A.toInt()
    }
}
