package com.remotevoice.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面（v2 对讲式）：连接状态条 + 设备芯片（单选激活）+ 底部按住说话（PTT）。
 * 服务器/指纹等一次性配置收在 [SettingsActivity]；
 * 每台 Mac 的秘密保存在设备条目中（新增即激活），按住说话即传输。
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: DeviceStore
    private lateinit var statusView: TextView
    private lateinit var devicesRow: LinearLayout
    private lateinit var pttBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private val pollHandler = Handler(Looper.getMainLooper())

    private val pollTask = object : Runnable {
        override fun run() {
            val st = AudioStreamService.Status
            val line = if (st.state == StatusState.STREAMING) {
                "${st.text}\n已发送 ${st.framesSent} 帧"
            } else {
                st.text
            }
            statusView.text = line
            statusView.setTextColor(
                when (st.state) {
                    StatusState.STREAMING -> COLOR_GREEN
                    StatusState.CONNECTING,
                    StatusState.WAIT_PEER -> COLOR_BLUE
                    StatusState.ERROR -> COLOR_RED
                    else -> COLOR_GRAY
                }
            )
            startBtn.isEnabled = st.state == StatusState.STOPPED ||
                st.state == StatusState.ERROR
            stopBtn.isEnabled = !startBtn.isEnabled
            pollHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        store = DeviceStore(this)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.text_status)
        devicesRow = findViewById(R.id.devices_row)
        pttBtn = findViewById(R.id.btn_ptt)
        startBtn = findViewById(R.id.btn_start)
        stopBtn = findViewById(R.id.btn_stop)

        renderDevices()

        // PTT：按下即传、松开即停（边沿由 ACTION_DOWN/UP/CANCEL 保证；滑出也可停）
        pttBtn.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pttBtn.isPressed = true
                    val svc = AudioStreamService.instance
                    if (svc == null) {
                        Toast.makeText(this, "请先点击「连接」开启中继", Toast.LENGTH_SHORT).show()
                    } else {
                        svc.setTalking(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pttBtn.isPressed = false
                    AudioStreamService.instance?.setTalking(false)
                    true
                }
                else -> false
            }
        }

        startBtn.setOnClickListener {
            val active = store.active()
            if (active == null) {
                showAddDeviceDialog()
                return@setOnClickListener
            }
            requestPermissionsThenStart()
        }
        stopBtn.setOnClickListener {
            startService(
                Intent(this, AudioStreamService::class.java)
                    .setAction(AudioStreamService.ACTION_STOP)
            )
        }
    }

    // ---- 设备芯片 ----

    private fun renderDevices() {
        devicesRow.removeAllViews()
        val devices = store.list()
        for (d in devices) {
            val active = store.active()?.id == d.id
            val chip = TextView(this).apply {
                text = (if (active) "● " else "○ ") + d.name.ifBlank { d.secret.take(10) }
                textSize = 13f
                setTextColor(if (active) COLOR_GREEN else COLOR_GRAY)
                setBackgroundResource(
                    if (active) R.drawable.chip_active else R.drawable.chip
                )
                setPadding(26, 14, 26, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = 16 }
            }
            chip.setOnClickListener {
                store.activate(d.id)
                renderDevices()
                Toast.makeText(this, "已切换到 ${d.name.ifBlank { "设备" }}", Toast.LENGTH_SHORT).show()
            }
            chip.setOnLongClickListener {
                showDeviceMenu(d)
                true
            }
            devicesRow.addView(chip)
        }
        val add = TextView(this).apply {
            text = "＋ 添加"
            textSize = 13f
            setTextColor(COLOR_GRAY)
            setBackgroundResource(R.drawable.chip_add)
            setPadding(26, 14, 26, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        add.setOnClickListener { showAddDeviceDialog() }
        devicesRow.addView(add)
    }

    private fun showDeviceMenu(d: DeviceStore.Device) {
        val menu = arrayOf("重命名 / 修改秘密", "删除设备")
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
            .setTitle("借用 Mac 的秘密添加设备")
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

    /** 类型启发：≥12 位视为永久，否则视为临时（仅用于展示，不影响认证）。 */
    private fun typeOf(secret: String): String {
        val norm = secret.filter { it != ' ' && it != '-' }
        return if (norm.length >= 12) "perm" else "temp"
    }

    // ---- 服务启停 ----

    override fun onResume() {
        super.onResume()
        renderDevices()
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
        const val REQ_PERMS = 1

        val COLOR_GREEN = 0xFF2E7D32.toInt()
        val COLOR_BLUE = 0xFF1565C0.toInt()
        val COLOR_RED = 0xFFC62828.toInt()
        val COLOR_GRAY = 0xFF8A8A8A.toInt()
    }
}
