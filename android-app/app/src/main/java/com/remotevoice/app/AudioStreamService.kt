package com.remotevoice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** UI 状态枚举：Activity 据此着色并控制按钮可用性。 */
enum class StatusState { STOPPED, CONNECTING, WAIT_PEER, STREAMING, ERROR }

/**
 * 前台采音服务（v2）：持有 RelayClient 与 AudioRecord 生命周期。
 * 前台服务(microphone 类型)保证后台采音不被系统杀死（设计文档 §6.3）。
 * v2 交互：按住说话（PTT）——采音循环常开，但仅在「按住或免提常开」时发送；
 * 设备名在认证成功后由 AUTH_OK 回写 [RelayClient.Listener.onPeerName]。
 */
class AudioStreamService : Service(), RelayClient.Listener {

    private var client: RelayClient? = null
    private var clientThread: Thread? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 免提常开：设置为 true 时相当于 v1 持续推流（默认关闭=仅按住说话）。 */
    @Volatile private var handsfree = false
    @Volatile private var pttHeld = false

    // UI 轮询的状态持有者（零依赖方案：Activity 每 500ms 读取）
    object Status {
        @Volatile var state: StatusState = StatusState.STOPPED
        @Volatile var text: String = "未启动"
        @Volatile var framesSent: Long = 0
        @Volatile var peerName: String = ""
        @Volatile var talking: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startStreaming()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopStreaming()
        Status.state = StatusState.STOPPED
        Status.text = "未启动"
        Status.talking = false
        super.onDestroy()
    }

    private fun startStreaming() {
        if (clientThread?.isAlive == true) return

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        handsfree = prefs.getBoolean(KEY_HANDSFREE, false)
        // 服务器地址：prefs 为空时回落内置默认（需求：默认服务器免输入）
        val server = (prefs.getString(KEY_SERVER, "") ?: "")
            .ifBlank { DEFAULT_SERVER }
        val fingerprint = prefs.getString(KEY_FINGERPRINT, "") ?: ""
        val host = server.substringBeforeLast(":")
        val port = server.substringAfterLast(":").toIntOrNull()
        if (host.isEmpty() || port == null) {
            Status.state = StatusState.ERROR
            Status.text = "错误：服务器地址无效（设置中检查）"
            stopSelf()
            return
        }
        if (fingerprint.isEmpty()) {
            Status.state = StatusState.ERROR
            Status.text = "错误：未配置服务端指纹（设置中填写）"
            stopSelf()
            return
        }
        // 激活设备：秘密原文（规范化后本地算哈希，只传 hex 给 relay）
        val device = DeviceStore(this).active()
        if (device == null || device.secret.isBlank()) {
            Status.state = StatusState.ERROR
            Status.text = "错误：尚无激活设备，请添加 Mac 秘密"
            stopSelf()
            return
        }
        val secretHex = secretHashHex(device.secret)

        Status.framesSent = 0
        Status.peerName = device.name
        Status.talking = false
        startForegroundWith("启动中…")
        Status.state = StatusState.CONNECTING
        Status.text = "启动中…"
        client = RelayClient(host, port, secretHex, fingerprint, this)
        clientThread = Thread({ client?.runForever() }, "relay-client").apply {
            start()
        }
    }

    private fun stopStreaming() {
        captureThread?.interrupt()
        captureThread = null
        client?.stop()
        clientThread = null
        audioRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (_: IllegalStateException) {
            }
        }
        audioRecord = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- RelayClient.Listener：状态回调均来自网络线程，仅做赋值与通知更新 ----

    override fun onState(text: String) {
        // 状态归一：RelayClient 的文案 → UI 状态枚举（Activity 据此着色/禁用按钮）
        Status.state = when {
            text == "推流中" || text.startsWith("传输中") -> StatusState.STREAMING
            text.contains("等待") -> StatusState.WAIT_PEER
            text == "已停止" -> StatusState.STOPPED
            else -> StatusState.CONNECTING
        }
        Status.text = text
        mainHandler.post { updateNotification(text) }
    }

    override fun onPeerName(name: String) {
        if (name.isBlank()) return
        Status.peerName = name
        // 首次连接自动命名：设备条目无别名时用 Mac 回传名补全（开放问题②落地）
        DeviceStore(this).fillNameIfEmpty(
            DeviceStore(this).activeId(), name
        )
    }

    override fun onPeerOnline() {
        // 回调来自网络线程：采音生命周期变更统一归到主线程，消除跨线程竞态（Review L-3）
        mainHandler.post { startCapture() }
    }

    override fun onPeerOffline() {
        mainHandler.post { stopCapture() }
    }

    override fun onFatal(message: String) {
        Status.state = StatusState.ERROR
        Status.text = "错误：$message"
        mainHandler.post {
            updateNotification("错误：$message")
            stopSelf()
        }
    }

    // ---- PTT 门控（Activity 线程调用） ----

    /** 按住说话：按下开始传输、松开停止（仅影响发送，不影响连接）。 */
    fun setTalking(on: Boolean) {
        pttHeld = on
        Status.talking = on
        mainHandler.post { updateNotification(if (on) "正在传输…" else Status.text) }
    }

    /** 是否满足发送条件（按住 或 免提常开）。 */
    private fun shouldSend(): Boolean = handsfree || pttHeld

    // ---- 采音循环：仅在对端在线期间运行 ----

    private fun startCapture() {
        if (captureThread?.isAlive == true) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val useAec = prefs.getBoolean(KEY_AEC, false)
        val source = if (useAec) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.MIC

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, FRAME_BYTES * 4)
        val record = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Status.text = "错误：AudioRecord 初始化失败（麦克风被占用？）"
            stopSelf()
            return
        }
        audioRecord = record
        // VOICE_COMMUNICATION 模式下系统会启用硬件级 AEC/NS（设计文档 §6.3）

        captureThread = Thread({
            val frame = ByteArray(FRAME_BYTES)
            record.startRecording()
            while (!Thread.currentThread().isInterrupted && audioRecord === record) {
                val n = record.read(frame, 0, FRAME_BYTES)
                if (n == FRAME_BYTES) {
                    // PTT 门控：未按住且非免提常开时，帧读出即弃（麦克风保持取音，
                    // 数据不出本机；对端掉线时 sendAudio 返回 false，同样不计数）
                    if (shouldSend() && client?.sendAudio(frame) == true) Status.framesSent++
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read 返回 $n")
                    break
                }
            }
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }, "audio-capture").apply { start() }
    }

    private fun stopCapture() {
        val t = captureThread
        captureThread = null
        t?.interrupt()
        // AudioRecord.read 阻塞调用不响应 interrupt，必须释放实例以解除阻塞；
        // 释放后采音线程的 audioRecord === record 判断失效，自行收尾退出
        audioRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (_: IllegalStateException) {
            }
        }
        audioRecord = null
    }

    // ---- 通知 ----

    private fun startForegroundWith(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "远程语音推流", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = buildNotification(text, pi)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(NOTIFY_ID, buildNotification(text, pi))
    }

    private fun buildNotification(text: String, contentIntent: PendingIntent): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("远程麦克风")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

    companion object {
        // 供外部组件（MainActivity）使用的动作与规格常量
        const val ACTION_STOP = "com.remotevoice.app.STOP"

        // 默认服务器（需求指定），prefs 未配置时回落使用
        const val DEFAULT_SERVER = "43.139.226.138:9432"

        // 音频规格：48kHz/mono/s16le/20ms 帧（设计文档 §4.1，与服务器/接收器一致）
        const val SAMPLE_RATE = 48000
        const val FRAME_BYTES = 1920

        /** 当前服务实例（MainActivity PT T 门控调用）；null=未在运行。 */
        @Volatile var instance: AudioStreamService? = null
            private set

        /** 秘密规范化（去空格/连字符+大写）后 SHA-256 hex——传输与 relay 一致。 */
        fun secretHashHex(secret: String): String {
            val norm = secret.filter { it != ' ' && it != '-' }.uppercase()
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(norm.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        // 仅内部使用
        private const val TAG = "AudioStreamService"
        private const val CHANNEL_ID = "relay_stream"
        private const val NOTIFY_ID = 1
        private const val PREFS = "config"
        private const val KEY_SERVER = "server"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_AEC = "aec"
        const val KEY_HANDSFREE = "handsfree"
    }
}
