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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面（协议 v4 服务器密码 + Mac 目录）：
 * - 状态胶囊 = 服务器连通开关（登录会话）：点按连接/停止/重试；
 * - Mac 列表（服务器 LIST 目录，离线也可见）：在线可点「连接」，已连接高亮，长按断开；
 * - 中央控制区显示当前控制目标的波形/计时；左右滑动在已连接的 Mac 间切换；
 * - PTT 底部大按钮按住说话（只发给当前控制目标）。
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
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
    private lateinit var btnRetry: Button
    private lateinit var btnRetrust: Button
    private lateinit var macsRow: LinearLayout
    private lateinit var pttBtn: Button
    private val pollHandler = Handler(Looper.getMainLooper())
    private var permissionRequestInFlight = false
    private var lastMacsRevision = -1L
    private lateinit var gestureDetector: GestureDetector

    private val pollTask = object : Runnable {
        override fun run() {
            render()
            pollHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(AudioStreamService.PREFS, MODE_PRIVATE)
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
        btnRetry = findViewById(R.id.btn_retry)
        btnRetrust = findViewById(R.id.btn_retrust)
        macsRow = findViewById(R.id.devices_row)
        pttBtn = findViewById(R.id.btn_ptt)

        renderMacs()

        // 状态胶囊 = 服务器连接开关：点按连接/停止（错误态点按语义是重试，见 toggleService）
        findViewById<View>(R.id.status_capsule).setOnClickListener { toggleService() }
        findViewById<View>(R.id.btn_retry).setOnClickListener { toggleService() }
        // 证书更换恢复：清记录并重连（PLAN-025）
        btnRetrust.setOnClickListener {
            AudioStreamService.retrustAndRestart(this)
            Toast.makeText(this, "已重新信任，正在重连", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 左右滑动切换已连接的 Mac（零依赖：GestureDetector fling）
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                if (kotlin.math.abs(dx) < 80 || kotlin.math.abs(velocityX) < 600) return false
                if (kotlin.math.abs(velocityY) > kotlin.math.abs(velocityX)) return false
                val connected = AudioStreamService.Status.connectedMacs
                if (connected.size < 2) return false
                val current = AudioStreamService.Status.activeMacId
                val index = connected.indexOf(current).coerceAtLeast(0)
                val next = if (dx < 0) {
                    connected[(index + 1) % connected.size]
                } else {
                    connected[(index - 1 + connected.size) % connected.size]
                }
                setActiveMac(next)
                Toast.makeText(this@MainActivity, "已切换到 ${macName(next)}", Toast.LENGTH_SHORT).show()
                return true
            }
        })
        liveBox.setOnTouchListener { _, ev ->
            gestureDetector.onTouchEvent(ev)
            true
        }
        bigState.setOnTouchListener { _, ev ->
            gestureDetector.onTouchEvent(ev)
            true
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

    private fun serverDisplay(): String {
        val s = ServerStore(this).active() ?: return AudioStreamService.DEFAULT_SERVER
        return "${s.host}:${s.port}"
    }

    private fun macName(deviceId: String): String =
        ServerStore(this).macCache().firstOrNull { it.deviceId == deviceId }?.name
            ?.ifBlank { deviceId.take(10) } ?: deviceId.take(10)

    private fun render() {
        val st = AudioStreamService.Status
        val macs = ServerStore(this).macCache()
        if (st.macsRevision != lastMacsRevision) {
            lastMacsRevision = st.macsRevision
            renderMacs()
        }
        val name = st.peerName.ifBlank {
            macName(st.activeMacId)
        }

        // 状态胶囊：服务器连通标记（登录会话）为主行，副行显示当前 Mac 状态
        val (main, sub, color) = when {
            st.state == StatusState.ERROR ->
                Triple("连接失败 · 点按重试", serverDisplay(), COLOR_RED)
            st.serverOnline -> when (st.state) {
                StatusState.STREAMING ->
                    if (st.talking) Triple("按住传输 · $name", "服务器正常 · ${serverDisplay()}", COLOR_GREEN)
                    else Triple("已就绪 · $name", "按住下方按钮开始说话", COLOR_GREEN)
                StatusState.WAIT_PEER ->
                    Triple("已连服务器 · 未选 Mac", "在下方列表点一台 Mac 连接", COLOR_AMBER)
                else ->
                    Triple("服务器正常", "连接 Mac：${serverDisplay()}", COLOR_GREEN)
            }
            AudioStreamService.instance != null ->
                Triple("连接服务器…", serverDisplay(), COLOR_BLUE)
            else ->
                Triple(getString(R.string.status_default), "服务器：${serverDisplay()}", COLOR_GRAY)
        }
        textStatus.text = main
        textSub.text = sub
        led.background.mutate().setTint(color)

        // 中央态区
        val showBig = st.state == StatusState.STOPPED || st.state == StatusState.WAIT_PEER ||
            (st.serverOnline && st.activeMacId.isEmpty())
        bigState.visibility = if (showBig) View.VISIBLE else View.GONE
        if (showBig) {
            if (st.activeMacId.isEmpty()) {
                bigT1.text = "选择一台 Mac"
                bigT2.text = "在下方列表点「连接」，或扫 Mac 上的二维码"
            } else if (st.state == StatusState.WAIT_PEER) {
                bigT1.text = getString(R.string.big_wait_t1)
                bigT2.text = getString(R.string.big_wait_t2)
            } else {
                bigT1.text = getString(R.string.big_idle_t1)
                bigT2.text = getString(R.string.big_idle_t2)
            }
        }
        spin.visibility =
            if (!st.serverOnline && AudioStreamService.instance != null) View.VISIBLE else View.GONE
        errPanel.visibility = if (st.state == StatusState.ERROR) View.VISIBLE else View.GONE
        if (st.state == StatusState.ERROR) textErr.text = st.text
        // 证书更换态：重试按钮换成「重新信任并重连」（普通重试无法自愈，必须清记录）
        val certPending = st.state == StatusState.ERROR && st.certChangePending
        btnRetrust.visibility = if (certPending) View.VISIBLE else View.GONE
        btnRetry.visibility = if (certPending) View.GONE else View.VISIBLE

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

    // ---- Mac 列表（服务器目录） ----

    private fun renderMacs() {
        macsRow.removeAllViews()
        val store = ServerStore(this)
        val macs = store.macCache()
        val connected = AudioStreamService.Status.connectedMacs
        val activeId = AudioStreamService.Status.activeMacId
        for (m in macs) {
            val isConnected = connected.contains(m.deviceId)
            val isActive = isConnected && m.deviceId == activeId
            val marker = when {
                isActive -> "◉ "
                isConnected -> "● "
                m.busy -> "⧖ "
                m.online -> "○ "
                else -> "· "
            }
            val chip = TextView(this).apply {
                text = marker + m.name.ifBlank { m.deviceId.take(10) } +
                    (if (isConnected && !isActive) "（点按切换）" else "")
                textSize = 12.5f
                setTextColor(
                    when {
                        isActive -> COLOR_GREEN
                        isConnected -> COLOR_GREEN
                        m.busy -> COLOR_AMBER
                        m.online -> COLOR_TX
                        else -> COLOR_TX2
                    }
                )
                setBackgroundResource(
                    when {
                        isActive -> R.drawable.chip_active
                        m.online || isConnected -> R.drawable.chip
                        else -> R.drawable.chip_add
                    }
                )
                setPadding(28, 14, 28, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = 16 }
            }
            chip.setOnClickListener {
                when {
                    isConnected -> setActiveMac(m.deviceId)
                    m.online -> connectMac(m.deviceId)
                    m.busy -> Toast.makeText(this, "该 Mac 正被其他会话使用", Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(this, "该 Mac 离线", Toast.LENGTH_SHORT).show()
                }
            }
            if (isConnected) {
                chip.setOnLongClickListener {
                    disconnectMac(m.deviceId)
                    true
                }
            }
            macsRow.addView(chip)
        }
        if (macs.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无 Mac：先在 Mac 端设置服务器地址与密码，点「＋ 添加」录入"
                textSize = 12f
                setTextColor(COLOR_TX2)
                setPadding(28, 14, 28, 14)
            }
            macsRow.addView(empty)
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
        add.setOnClickListener { showAddServerDialog() }
        macsRow.addView(add)
    }

    // ---- 连接动作 ----

    private fun connectMac(deviceId: String) {
        prefs.edit().putBoolean(AudioStreamService.KEY_USER_STOPPED, false).apply()
        prefs.edit().putString(AudioStreamService.KEY_BRIDGE_TARGET, deviceId).apply()
        val intent = Intent(this, AudioStreamService::class.java)
            .setAction(AudioStreamService.ACTION_SET_ACTIVE)
            .putExtra(AudioStreamService.EXTRA_DEVICE_ID, deviceId)
        if (AudioStreamService.instance != null) {
            startService(intent)
        } else {
            startForegroundService(intent)
        }
        Toast.makeText(this, "连接 ${macName(deviceId)}…", Toast.LENGTH_SHORT).show()
    }

    private fun setActiveMac(deviceId: String) {
        startService(
            Intent(this, AudioStreamService::class.java)
                .setAction(AudioStreamService.ACTION_SET_ACTIVE)
                .putExtra(AudioStreamService.EXTRA_DEVICE_ID, deviceId)
        )
    }

    private fun disconnectMac(deviceId: String) {
        startService(
            Intent(this, AudioStreamService::class.java)
                .setAction(AudioStreamService.ACTION_DISCONNECT_MAC)
                .putExtra(AudioStreamService.EXTRA_DEVICE_ID, deviceId)
        )
    }

    private fun toggleService() {
        val running = AudioStreamService.instance != null
        if (!running) {
            prefs.edit().putBoolean(AudioStreamService.KEY_USER_STOPPED, false).apply()
            maybeAutoStart(force = true)
            Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
        } else if (AudioStreamService.Status.state == StatusState.ERROR &&
            !AudioStreamService.Status.certChangePending
        ) {
            // 错误状态下，点按的语义是用户明确重试，而不是再次停止。
            restartService()
        } else if (AudioStreamService.Status.state != StatusState.ERROR) {
            startService(
                Intent(this, AudioStreamService::class.java)
                    .setAction(AudioStreamService.ACTION_STOP)
            )
            prefs.edit().putBoolean(AudioStreamService.KEY_USER_STOPPED, true).apply()
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartService() {
        prefs.edit().putBoolean(AudioStreamService.KEY_USER_STOPPED, false).apply()
        AudioStreamService.prepareRetry()
        startService(
            Intent(this, AudioStreamService::class.java)
                .setAction(AudioStreamService.ACTION_RESTART)
        )
    }

    // ---- 添加服务器（手动 / 扫码） ----

    private fun showAddServerDialog() {
        val address = EditText(this).apply {
            hint = "服务器地址（IP:端口 或 域名:端口）"
            setText(serverDisplay())
            setSingleLine(true)
        }
        val password = EditText(this).apply {
            hint = "服务器密码（Mac 端「显示密码」可得）"
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(address)
            addView(password)
        }
        AlertDialog.Builder(this)
            .setTitle("添加服务器")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val parsed = ConfigParser.parse(address.text.toString().trim())
                val pwd = password.text.toString().trim()
                if (parsed == null) {
                    Toast.makeText(this, R.string.toast_addr_bad, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (pwd.isEmpty()) {
                    Toast.makeText(this, "密码不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ServerStore(this).add(parsed.host, parsed.port, pwd)
                onServerChanged()
            }
            .setNeutralButton("扫码配对") { d, _ ->
                d.dismiss()
                @Suppress("DEPRECATION") // 零 androidx：用经典 startActivityForResult（设计 §4.1）
                startActivityForResult(Intent(this, ScanActivity::class.java), REQ_SCAN)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 扫码配对落地（rv[s]://host:port?s=<服务器密码>&n=<Mac名>）。 */
    private fun applyPairing(payload: String) {
        val pairing = ConfigParser.parsePairing(payload)
        if (pairing == null) {
            Toast.makeText(this, "配对码无效（缺密码或地址非法）", Toast.LENGTH_LONG).show()
            return
        }
        val target = ConfigParser.format(ConfigParser.Parsed(pairing.host, pairing.port, pairing.mode))
        ServerStore(this).add(pairing.host, pairing.port, pairing.secret)
        Toast.makeText(this, "已添加服务器 $target", Toast.LENGTH_LONG).show()
        onServerChanged()
    }

    /** 服务器变化后重启连接（目录与桥接都建立在当前服务器上）。 */
    private fun onServerChanged() {
        renderMacs()
        render()
        if (AudioStreamService.instance != null) {
            restartService()
        } else {
            maybeAutoStart(force = true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK) return
        val payload = data?.getStringExtra(ScanActivity.EXTRA_PAYLOAD)
        if (payload.isNullOrBlank()) {
            Toast.makeText(this, "扫码结果为空", Toast.LENGTH_SHORT).show()
            return
        }
        applyPairing(payload)
    }

    // ---- 服务启停（自动连接） ----

    override fun onResume() {
        super.onResume()
        renderMacs()
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

    /** 一键连接：未配置服务器不自动拉起；其余情况按用户开关自动连接。 */
    private fun maybeAutoStart(force: Boolean = false) {
        if (AudioStreamService.instance != null) return
        if (prefs.getBoolean(AudioStreamService.KEY_USER_STOPPED, false)) return
        if (ServerStore(this).active()?.password.isNullOrBlank()) return
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
        const val REQ_PERMS = 1
        const val REQ_SCAN = 2

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
