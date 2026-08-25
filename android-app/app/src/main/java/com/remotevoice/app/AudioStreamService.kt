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
 * 前台采音服务：持有 RelayClient 与 AudioRecord 生命周期。
 * 前台服务(microphone 类型)保证后台采音不被系统杀死（设计文档 §6.3）。
 */
class AudioStreamService : Service(), RelayClient.Listener {

    private var client: RelayClient? = null
    private var clientThread: Thread? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // UI 轮询的状态持有者（零依赖方案：Activity 每 500ms 读取）
    object Status {
        @Volatile var state: StatusState = StatusState.STOPPED
        @Volatile var text: String = "未启动"
        @Volatile var framesSent: Long = 0
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
        stopStreaming()
        Status.state = StatusState.STOPPED
        Status.text = "未启动"
        super.onDestroy()
    }

    private fun startStreaming() {
        if (clientThread?.isAlive == true) return

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 服务器地址：prefs 为空时回落内置默认（需求：默认服务器免输入）
        val server = (prefs.getString(KEY_SERVER, "") ?: "")
            .ifBlank { DEFAULT_SERVER }
        val token = prefs.getString(KEY_TOKEN, "") ?: ""
        val fingerprint = prefs.getString(KEY_FINGERPRINT, "") ?: ""
        val host = server.substringBeforeLast(":")
        val port = server.substringAfterLast(":").toIntOrNull()
        if (host.isEmpty() || port == null) {
            Status.state = StatusState.ERROR
            Status.text = "错误：服务器地址无效（设置中检查）"
            stopSelf()
            return
        }
        if (token.isEmpty()) {
            Status.state = StatusState.ERROR
            Status.text = "错误：未设置连接密码"
            stopSelf()
            return
        }
        if (fingerprint.isEmpty()) {
            Status.state = StatusState.ERROR
            Status.text = "错误：未配置服务端指纹（设置中填写）"
            stopSelf()
            return
        }

        Status.framesSent = 0
        startForegroundWith("启动中…")
        Status.state = StatusState.CONNECTING
        Status.text = "启动中…"
        client = RelayClient(host, port, token, fingerprint, this)
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
            text == "推流中" -> StatusState.STREAMING
            text.contains("等待") -> StatusState.WAIT_PEER
            text == "已停止" -> StatusState.STOPPED
            else -> StatusState.CONNECTING
        }
        Status.text = text
        mainHandler.post { updateNotification(text) }
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
                    // 对端掉线时 sendAudio 返回 false，帧自然丢弃且不计数
                    if (client?.sendAudio(frame) == true) Status.framesSent++
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

        // 仅内部使用
        private const val TAG = "AudioStreamService"
        private const val CHANNEL_ID = "relay_stream"
        private const val NOTIFY_ID = 1
        private const val PREFS = "config"
        private const val KEY_SERVER = "server"
        private const val KEY_TOKEN = "token"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_AEC = "aec"
    }
}
