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
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面：服务器/token/证书指纹配置 + 启停控制 + 状态展示。
 * 权限齐备后才允许启动前台服务（RECORD_AUDIO 为运行时权限）。
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private val pollHandler = Handler(Looper.getMainLooper())

    private val pollTask = object : Runnable {
        override fun run() {
            statusView.text = AudioStreamService.Status.text
            pollHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("config", MODE_PRIVATE)
        setContentView(R.layout.activity_main)

        val serverEdit = findViewById<EditText>(R.id.edit_server)
        val tokenEdit = findViewById<EditText>(R.id.edit_token)
        val fpEdit = findViewById<EditText>(R.id.edit_fingerprint)
        val aecSwitch = findViewById<Switch>(R.id.switch_aec)
        val stopBtn = findViewById<Button>(R.id.btn_stop)
        statusView = findViewById(R.id.text_status)
        startBtn = findViewById(R.id.btn_start)

        serverEdit.setText(prefs.getString("server", ""))
        tokenEdit.setText(prefs.getString("token", ""))
        fpEdit.setText(prefs.getString("fingerprint", ""))
        aecSwitch.isChecked = prefs.getBoolean("aec", false)

        startBtn.setOnClickListener {
            val server = serverEdit.text.toString().trim()
            val token = tokenEdit.text.toString().trim()
            val fp = fpEdit.text.toString().replace(":", "").replace(" ", "").trim()
            if (server.isEmpty() || token.isEmpty() || fp.isEmpty()) {
                Toast.makeText(this, R.string.err_incomplete, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("server", server)
                .putString("token", token)
                .putString("fingerprint", fp)
                .putBoolean("aec", aecSwitch.isChecked)
                .apply()
            requestPermissionsThenStart()
        }
        stopBtn.setOnClickListener {
            startService(Intent(this, AudioStreamService::class.java).setAction(AudioStreamService.ACTION_STOP))
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
        const val REQ_PERMS = 1
    }
}
