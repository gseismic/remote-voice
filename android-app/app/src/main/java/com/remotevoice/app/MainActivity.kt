package com.remotevoice.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Button

/**
 * 主界面（傻瓜式一键连接）：打开即自动连接（配置齐全时），状态条即连接态。
 * 设备芯片单选：切换即重连；PTT 底部大按钮按住说话（边沿由 ACTION_DOWN/UP/CANCEL 保证）。
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: DeviceStore
    private lateinit var statusView: TextView
    private lateinit var devicesRow: LinearLayout
    private lateinit var pttBtn: Button
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pttDownAt = 0L
    private var permissionRequestInFlight = false

    private val pollTask = object : Runnable {
        override fun run() {
            val st = AudioStreamService.Status
            val line = if (st.state == StatusState.STREAMING) {
                var t = st.text
                if (st.talking && pttDownAt > 0) {
                    t += "\n已按住 ${(System.currentTimeMillis() - pttDownAt) / 1000}s · 已发送 ${st.framesSent} 帧"
                } else {
                    t += "\n已发送 ${st.framesSent} 帧"
                }
                t
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

        renderDevices()

        // 状态条 = 连接开关：点按连接/停止（停止后进入手动模式，onResume 不自动拉起）
        statusView.setOnClickListener { toggleService() }

        // PTT：按下即传、松开即停
        pttBtn.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pttBtn.isPressed = true
                    val svc = AudioStreamService.instance
                    if (svc == null) {
                        if (AudioStreamService.Status.state != StatusState.ERROR) {
                            maybeAutoStart()
                        }
                        Toast.makeText(this, "连接中，稍候再按住说话", Toast.LENGTH_SHORT).show()
                    } else {
                        pttDownAt = System.currentTimeMillis()
                        svc.setTalking(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pttBtn.isPressed = false
                    pttDownAt = 0L
                    AudioStreamService.instance?.setTalking(false)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // ---- 一键连接 ----

    private fun toggleService() {
        val running = AudioStreamService.instance != null
        if (!running) {
            prefs.edit().putBoolean(KEY_USER_STOPPED, false).apply()
            maybeAutoStart(force = true)
            Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
        } else if (AudioStreamService.Status.state == StatusState.ERROR) {
            // 错误状态下，状态条点击的语义是用户明确重试，而不是再次停止。
            restartForActiveDevice()
        } else {
            startService(
                Intent(this, AudioStreamService::class.java).setAction(AudioStreamService.ACTION_STOP)
            )
            prefs.edit().putBoolean(KEY_USER_STOPPED, true).apply()
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    /** 设备芯片：激活 + 正在运行时切换即重连（与设计稿"切换即重连"一致）。 */
    private fun activateAndReconnect(d: DeviceStore.Device) {
        store.activate(d.id)
        prefs.edit().putBoolean(KEY_USER_STOPPED, false).apply()
        renderDevices()
        Toast.makeText(this, "已切换到 ${d.name.ifBlank { "设备" }}", Toast.LENGTH_SHORT).show()
        if (AudioStreamService.instance != null) {
            restartForActiveDevice()
        } else {
            maybeAutoStart(force = true)
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
            chip.setOnClickListener { activateAndReconnect(d) }
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
            .setTitle("借用 Mac 的秘密添加设备")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val s = secret.text.toString().trim()
                if (s.isEmpty()) {
                    Toast.makeText(this, "秘密不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.add(alias.text.toString().trim(), s, typeOf(s))
                prefs.edit().putBoolean(KEY_USER_STOPPED, false).apply()
                renderDevices()
                restartForActiveDevice()
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
                if (store.activeId() == d.id) restartForActiveDevice()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 类型启发：≥12 位视为永久，否则视为临时（仅用于展示，不影响认证）。 */
    private fun typeOf(secret: String): String {
        val norm = secret.filter { it != ' ' && it != '-' }
        return if (norm.length >= 12) "perm" else "temp"
    }

    /** 新增或切换设备后重启服务，让当前激活设备立即接管连接。 */
    private fun restartForActiveDevice() {
        prefs.edit().putBoolean(KEY_USER_STOPPED, false).apply()
        AudioStreamService.prepareRetry()
        if (AudioStreamService.instance != null) {
            // 由服务自己串行停止旧 relay 并启动新 relay，避免固定延迟造成竞态。
            startService(
                Intent(this, AudioStreamService::class.java).setAction(AudioStreamService.ACTION_RESTART)
            )
        } else {
            maybeAutoStart()
        }
    }

    /** 删除当前设备后切换到剩余设备；没有设备时停止前台服务。 */
    private fun reconnectAfterDeviceRemoval() {
        if (store.active() != null) {
            restartForActiveDevice()
            return
        }
        prefs.edit().putBoolean(KEY_USER_STOPPED, true).apply()
        if (AudioStreamService.instance != null) {
            startService(
                Intent(this, AudioStreamService::class.java).setAction(AudioStreamService.ACTION_STOP)
            )
        }
    }

    // ---- 服务启停（自动连接） ----

    override fun onResume() {
        super.onResume()
        renderDevices()
        pollHandler.post(pollTask)
        maybeAutoStart()
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollTask)
    }

    /** 一键连接：无激活设备→引导添加；有设备且用户未手动停止→自动拉起服务。 */
    private fun maybeAutoStart(force: Boolean = false) {
        if (AudioStreamService.instance != null) return
        if (prefs.getBoolean(KEY_USER_STOPPED, false)) return
        if (store.active() == null) return
        if (!force && AudioStreamService.Status.state == StatusState.ERROR) return
        if (force) AudioStreamService.prepareRetry()
        requestPermissionsThenStart()
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
        if (permissionRequestInFlight) return
        // 通知权限只影响状态栏可见性，不能阻断前台采音连接。
        val denied = listOf(Manifest.permission.RECORD_AUDIO).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            doStartService()
        } else {
            permissionRequestInFlight = true
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
        permissionRequestInFlight = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            doStartService()
        } else {
            AudioStreamService.Status.state = StatusState.ERROR
            AudioStreamService.Status.text = "错误：未授予麦克风权限，无法连接"
            Toast.makeText(this, R.string.err_no_mic_perm, Toast.LENGTH_LONG).show()
        }
    }

    private fun doStartService() {
        // 服务内部有 clientThread 存活检查，重复点击安全
        try {
            startForegroundService(Intent(this, AudioStreamService::class.java))
        } catch (_: RuntimeException) {
            AudioStreamService.Status.state = StatusState.ERROR
            AudioStreamService.Status.text = "错误：系统拒绝启动麦克风服务"
            Toast.makeText(this, "系统拒绝启动麦克风服务，请检查权限", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val PREFS = "config"
        const val KEY_USER_STOPPED = "user_stopped"
        const val REQ_PERMS = 1

        val COLOR_GREEN = 0xFF2E7D32.toInt()
        val COLOR_BLUE = 0xFF1565C0.toInt()
        val COLOR_RED = 0xFFC62828.toInt()
        val COLOR_GRAY = 0xFF8A8A8A.toInt()
    }
}
