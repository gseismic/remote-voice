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
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面（V3.2 原型 docs/ui-design/v3/android.html）：状态胶囊点按连接/停止/重试，
 * 中央态区展示大字/转圈/波形+计时+帧数/错误面板；设备芯片单选切换即重连；PTT 底部大按钮按住说话。
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: DeviceStore
    private lateinit var led: View
    private lateinit var textStatus: TextView
    private lateinit var textSub: TextView
    private lateinit var bigState: View
    private lateinit var bigT1: TextView
    private lateinit var bigT2: TextView
    private lateinit var spin: View
    private lateinit var liveBox: View
    private lateinit var wave: WaveView
    private lateinit var textTimer: TextView
    private lateinit var textFrames: TextView
    private lateinit var errPanel: View
    private lateinit var textErr: TextView
    private lateinit var devicesRow: LinearLayout
    private lateinit var pttBtn: Button
    private val pollHandler = Handler(Looper.getMainLooper())
    private var permissionRequestInFlight = false

    private val pollTask = object : Runnable {
        override fun run() {
            render()
            pollHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        store = DeviceStore(this)
        setContentView(R.layout.activity_main)

        led = findViewById(R.id.led)
        textStatus = findViewById(R.id.text_status)
        textSub = findViewById(R.id.text_sub)
        bigState = findViewById(R.id.big_state)
        bigT1 = findViewById(R.id.big_t1)
        bigT2 = findViewById(R.id.big_t2)
        spin = findViewById(R.id.spin)
        liveBox = findViewById(R.id.live_box)
        wave = findViewById(R.id.wave)
        textTimer = findViewById(R.id.text_timer)
        textFrames = findViewById(R.id.text_frames)
        errPanel = findViewById(R.id.err_panel)
        textErr = findViewById(R.id.text_err)
        devicesRow = findViewById(R.id.devices_row)
        pttBtn = findViewById(R.id.btn_ptt)

        renderDevices()

        // 状态胶囊 = 连接开关：点按连接/停止（错误态点按语义是重试，见 toggleService）
        findViewById<View>(R.id.status_capsule).setOnClickListener { toggleService() }
        findViewById<View>(R.id.btn_retry).setOnClickListener { toggleService() }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

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
    }

    // ---- 渲染（500ms 轮询 + 状态驱动） ----

    private fun serverDisplay(): String =
        prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }
            ?: AudioStreamService.DEFAULT_SERVER

    private fun render() {
        val st = AudioStreamService.Status
        val name = st.peerName.ifBlank { store.active()?.name.orEmpty() }

        // 状态胶囊（LED + 主行 + 副行）
        val (main, sub, color) = when (st.state) {
            StatusState.STREAMING ->
                if (st.talking) Triple("按住传输 · $name", "", COLOR_GREEN)
                else Triple("已就绪 · $name", "按住下方按钮开始说话", COLOR_GREEN)
            StatusState.WAIT_PEER -> Triple("已连中继 · 等待 Mac", serverDisplay(), COLOR_AMBER)
            StatusState.CONNECTING -> Triple("连接服务器…", serverDisplay(), COLOR_BLUE)
            StatusState.ERROR -> Triple("连接失败 · 点按重试", serverDisplay(), COLOR_RED)
            else -> Triple(getString(R.string.status_default), "服务器：${serverDisplay()}", COLOR_GRAY)
        }
        textStatus.text = main
        textSub.text = sub
        led.background.mutate().setTint(color)

        // 中央态区
        val showBig = st.state == StatusState.STOPPED || st.state == StatusState.WAIT_PEER
        bigState.visibility = if (showBig) View.VISIBLE else View.GONE
        if (showBig) {
            if (st.state == StatusState.WAIT_PEER) {
                bigT1.text = getString(R.string.big_wait_t1)
                bigT2.text = getString(R.string.big_wait_t2)
            } else {
                bigT1.text = getString(R.string.big_idle_t1)
                bigT2.text = getString(R.string.big_idle_t2)
            }
        }
        spin.visibility = if (st.state == StatusState.CONNECTING) View.VISIBLE else View.GONE
        errPanel.visibility = if (st.state == StatusState.ERROR) View.VISIBLE else View.GONE
        if (st.state == StatusState.ERROR) textErr.text = st.text

        val live = st.state == StatusState.STREAMING
        liveBox.visibility = if (live) View.VISIBLE else View.GONE
        wave.setActive(live)
        if (live) {
            val sec = if (st.startedAt > 0) {
                ((SystemClock.elapsedRealtime() - st.startedAt) / 1000).toInt()
            } else 0
            textTimer.text = "%02d:%02d".format(sec / 60, sec % 60)
            textFrames.text = "已发送 ${st.framesSent} 帧 · 48kHz"
        }
    }

    // ---- 连接开关 ----

    private fun toggleService() {
        val running = AudioStreamService.instance != null
        if (!running) {
            prefs.edit().putBoolean(KEY_USER_STOPPED, false).apply()
            maybeAutoStart(force = true)
            Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
        } else if (AudioStreamService.Status.state == StatusState.ERROR) {
            // 错误状态下，点按的语义是用户明确重试，而不是再次停止。
            restartForActiveDevice()
        } else {
            startService(
                Intent(this, AudioStreamService::class.java).setAction(AudioStreamService.ACTION_STOP)
            )
            prefs.edit().putBoolean(KEY_USER_STOPPED, true).apply()
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- 设备芯片（单选激活，切换即重连） ----

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

    private fun renderDevices() {
        devicesRow.removeAllViews()
        val devices = store.list()
        for (d in devices) {
            val active = store.active()?.id == d.id
            val chip = TextView(this).apply {
                text = (if (active) "● " else "○ ") + d.name.ifBlank { d.secret.take(10) }
                textSize = 12.5f
                setTextColor(if (active) COLOR_GREEN else COLOR_TX2)
                setBackgroundResource(if (active) R.drawable.chip_active else R.drawable.chip)
                setPadding(28, 14, 28, 14)
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
            textSize = 12.5f
            setTextColor(COLOR_TX2)
            setBackgroundResource(R.drawable.chip_add)
            setPadding(28, 14, 28, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        add.setOnClickListener { showAddDeviceDialog() }
        devicesRow.addView(add)
    }

    private fun showDeviceMenu(d: DeviceStore.Device) {
        val menu = arrayOf("重命名 / 修改密码", "删除设备")
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
            .setTitle("借用 Mac 的密码添加设备")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val s = secret.text.toString().trim()
                if (s.isEmpty()) {
                    Toast.makeText(this, "密码不能为空", Toast.LENGTH_SHORT).show()
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
        render()
        pollHandler.post(pollTask)
        maybeAutoStart()
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollTask)
        // PTT 兜底复位：息屏/来电/切走时 ACTION_UP 可能不送达，防止 pttHeld
        // 永久卡 true 导致持续推流（设计 §3.2 根因 B）
        AudioStreamService.instance?.setTalking(false)
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
        const val KEY_SERVER = "server"
        const val REQ_PERMS = 1

        // V3.2 tokens（values/colors.xml 同源；代码内着色用）
        val COLOR_GREEN = 0xFF3FB950.toInt()
        val COLOR_BLUE = 0xFF58A6FF.toInt()
        val COLOR_AMBER = 0xFFF5B83D.toInt()
        val COLOR_RED = 0xFFF85149.toInt()
        val COLOR_GRAY = 0xFF3D444D.toInt()
        val COLOR_TX = 0xFFE6E9EE.toInt()
        val COLOR_TX2 = 0xFF8B949E.toInt()
    }
}
