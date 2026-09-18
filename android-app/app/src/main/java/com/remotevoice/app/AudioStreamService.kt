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
import android.os.SystemClock
import android.util.Log

/** UI 状态枚举：Activity 据此着色并控制按钮可用性。 */
enum class StatusState { STOPPED, CONNECTING, WAIT_PEER, STREAMING, ERROR }

/**
 * 前台采音服务（v3）：持有 RelayClient 与 AudioRecord 生命周期。
 * 前台服务(microphone 类型)保证后台采音不被系统杀死（设计文档 §6.3）。
 * 当前交互：按住说话（PTT）——采音循环常开，仅在按住时发送；
 * 按下/松开即时上报 FRAME_TALK（Mac 端据此模拟 Fn 触发语音输入）；
 * 设备名在认证成功后由 AUTH_OK 回写 [RelayClient.Listener.onPeerName]。
 */
class AudioStreamService : Service(), RelayClient.Listener {

    @Volatile private var client: RelayClient? = null
    @Volatile private var clientThread: Thread? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null
    /** 每次对端上线/掉线递增，隔离同一 relay 自动重连前后的采音线程。 */
    @Volatile private var captureGeneration = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 免提常开（v1 持续推流）已随 V3.2 纯 PTT 语义移除；发送只受 pttHeld 门控。 */
    @Volatile private var pttHeld = false
    /** 致命错误停服后保留错误文案，避免 onDestroy 把真正原因覆盖成“未启动”。 */
    @Volatile private var preserveError = false
    /** 用于让旧错误的 stopSelfResult 不会误停掉已经重启的新连接。 */
    @Volatile private var activeStartId = 0
    /** 每次重启递增；主线程队列中的旧连接回调必须带着旧代次失效。 */
    @Volatile private var connectionGeneration = 0L

    // UI 轮询的状态持有者（零依赖方案：Activity 每 500ms 读取）
    object Status {
        @Volatile var state: StatusState = StatusState.STOPPED
        @Volatile var text: String = "未启动"
        @Volatile var framesSent: Long = 0
        @Volatile var peerName: String = ""
        @Volatile var talking: Boolean = false

        /** 进入传输态的时刻（elapsedRealtime）；0=非传输态。主界面计时用。 */
        @Volatile var startedAt: Long = 0
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                preserveError = false
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                stopStreaming()
                startStreaming(startId)
            }
            else -> startStreaming()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val keepError = preserveError
        instance = null
        stopStreaming()
        if (!keepError) {
            Status.state = StatusState.STOPPED
            Status.text = "已停止"
            Status.talking = false
        }
        super.onDestroy()
    }

    private fun startStreaming(startId: Int = activeStartId) {
        if (clientThread?.isAlive == true) return
        preserveError = false
        activeStartId = startId
        val generation = ++connectionGeneration
        // 前台服务必须尽早发布通知；即使配置或音频初始化失败，也要让系统知道启动已处理。
        try {
            startForegroundWith("启动中…")
        } catch (_: SecurityException) {
            failAndStop("系统拒绝启动麦克风服务，请检查权限")
            return
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pttHeld = false
        // 单一服务器地址（V3.2）：IP:端口 或 rv://IP:端口；本地测试直接填局域网 IP。
        val serverRaw = (prefs.getString(KEY_SERVER, "") ?: "").ifBlank { DEFAULT_SERVER }
        val server = ConfigParser.parse(serverRaw)
        if (server == null) {
            failAndStop("服务器地址无效（设置中检查）")
            return
        }
        // 指纹为空 = TOFU：连接时自动信任并记录（用户无需输入），按 host:port 隔离
        // 激活设备：秘密原文（规范化后本地算哈希，只传 hex 给 relay）
        val device = DeviceStore(this).active()
        if (device == null || device.secret.isBlank()) {
            failAndStop("尚无激活设备，请添加 Mac 秘密")
            return
        }
        val secretHex = secretHashHex(device.secret)

        Status.framesSent = 0
        Status.peerName = device.name
        Status.talking = false
        Status.state = StatusState.CONNECTING
        Status.text = "启动中…"
        lateinit var relay: RelayClient
        val relayListener = object : RelayClient.Listener {
            private fun isCurrent(): Boolean = client === relay && connectionGeneration == generation

            private fun postIfCurrent(action: () -> Unit) {
                mainHandler.post {
                    if (isCurrent()) action()
                }
            }

            override fun onState(text: String) {
                postIfCurrent { this@AudioStreamService.applyState(text) }
            }

            override fun onPeerName(name: String) {
                postIfCurrent { this@AudioStreamService.applyPeerName(name) }
            }

            override fun onPeerOnline() {
                postIfCurrent { this@AudioStreamService.startCapture() }
            }

            override fun onPeerOffline() {
                postIfCurrent { this@AudioStreamService.stopCapture() }
            }

            override fun onFatal(message: String) {
                postIfCurrent { this@AudioStreamService.failAndStop(message) }
            }

            override fun onPeerFingerprint(fingerprint: String, serverKey: String) {
                postIfCurrent {
                    this@AudioStreamService.rememberPeerFingerprint(fingerprint, serverKey)
                }
            }
        }
        relay = RelayClient(
            server.host,
            server.port,
            secretHex,
            { h, p -> TrustStore.load(prefs, TrustStore.serverKey(h, p)) },
            relayListener,
        )
        client = relay
        clientThread = Thread({ relay.runForever() }, "relay-client").apply {
            start()
        }
    }

    private fun stopStreaming() {
        // 先使已经排队的旧回调失效，再关闭底层连接和采音资源。
        connectionGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        val oldCaptureThread = captureThread
        captureGeneration++
        oldCaptureThread?.interrupt()
        val oldClient = client
        val oldThread = clientThread
        client = null
        clientThread = null
        oldClient?.stop()
        releaseAudioRecord()
        waitForThread(oldCaptureThread, 1000)
        if (captureThread === oldCaptureThread) captureThread = null
        oldThread?.interrupt()
        waitForThread(oldThread, 1000)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- RelayClient.Listener：统一在服务主线程更新状态与采音资源 ----

    override fun onState(text: String) {
        applyState(text)
    }

    private fun applyState(text: String) {
        // relay 线程在 fatal 回调后还会发送一次“已停止”，不能覆盖真正的错误原因。
        if (preserveError && text == "已停止") return
        // 状态归一：RelayClient 的文案 → UI 状态枚举（Activity 据此着色/禁用按钮）
        // 桥接建立=已就绪（STREAMING 态），但只在按住 PTT 后才真正发送
        Status.state = when {
            text == "已就绪" || text.startsWith("已就绪 ·") -> StatusState.STREAMING
            text.contains("等待") -> StatusState.WAIT_PEER
            text == "已停止" -> StatusState.STOPPED
            else -> StatusState.CONNECTING
        }
        // 传输计时起点：进入 STREAMING 时不计时（桥接≠说话），按住 PTT 时由
        // setTalking 打点；离开传输态一律清零（主界面 mm:ss 计时=按住时长）
        if (Status.state != StatusState.STREAMING) {
            Status.startedAt = 0L
        }
        Status.text = text
        mainHandler.post { updateNotification(text) }
    }

    override fun onPeerName(name: String) {
        applyPeerName(name)
    }

    private fun applyPeerName(name: String) {
        if (name.isBlank()) return
        Status.peerName = name
        // 首次连接自动命名：设备条目无别名时用 Mac 回传名补全（开放问题②落地）
        DeviceStore(this).fillNameIfEmpty(
            DeviceStore(this).activeId(), name
        )
    }

    override fun onPeerFingerprint(fingerprint: String, serverKey: String) {
        rememberPeerFingerprint(fingerprint, serverKey)
    }

    private fun rememberPeerFingerprint(fingerprint: String, serverKey: String) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        TrustStore.remember(prefs, serverKey, fingerprint)
        Log.i(TAG, "tofu: 已建立服务器信任")
        mainHandler.post {
            if (Status.state == StatusState.CONNECTING) {
                Status.text = "已连接中继（首次连接已建立信任）"
                updateNotification(Status.text)
            }
        }
    }

    override fun onPeerOnline() {
        // 兼容直接调用本监听器的路径；实际 relay 回调由带代次的代理直接调用 startCapture。
        mainHandler.post { startCapture() }
    }

    override fun onPeerOffline() {
        mainHandler.post { stopCapture() }
    }

    override fun onFatal(message: String) {
        failAndStop(message)
    }

    /** 记录可重试的错误并停止服务；错误状态会留在主界面直到用户主动重试。 */
    private fun failAndStop(message: String) {
        preserveError = true
        val startId = activeStartId
        val text = if (message.startsWith("错误：")) message else "错误：$message"
        Status.state = StatusState.ERROR
        Status.text = text
        Status.talking = false
        // 音频初始化失败或认证失败后立即停止网络重连，避免错误状态被后续连接事件冲掉。
        client?.stop()
        mainHandler.post {
            if (!preserveError || activeStartId != startId) return@post
            updateNotification(text)
            stopSelfResult(startId)
        }
    }

    // ---- PTT 门控（Activity 线程调用） ----

    /** 按住说话：按下开始传输、松开停止（仅影响发送，不影响连接）。 */
    fun setTalking(on: Boolean) {
        pttHeld = on
        Status.talking = on
        // 计时=按住时长：按下打点、松开清零（render 侧用 startedAt 显示 mm:ss）
        Status.startedAt = if (on) SystemClock.elapsedRealtime() else 0L
        // 即时告知 Mac 端说话状态（触发语音输入模拟）；未桥接时丢弃，桥接建立会重发
        client?.let { relay ->
            val serial = relay.currentConnectionSerial()
            relay.sendTalk(on, serial)
        }
        mainHandler.post { updateNotification(if (on) "正在传输…" else Status.text) }
    }

    /** 是否满足发送条件：纯 PTT 语义（V3.2），仅按住时发送。 */
    private fun shouldSend(): Boolean = pttHeld

    // ---- 采音循环：仅在对端在线期间运行 ----

    private fun startCapture() {
        if (captureThread?.isAlive == true) return
        val generation = connectionGeneration
        val captureId = ++captureGeneration
        // 固定本次采音对应的 relay；切换设备后旧线程绝不能重新读取到新 client。
        val captureClient = client ?: return
        val connectionSerial = captureClient.currentConnectionSerial()
        // 桥接建立/重连后同步当前 PTT 状态，避免 Mac 端 Fn 与手机不一致（设计 §1 时序保证）
        if (pttHeld) captureClient.sendTalk(true, connectionSerial)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val useAec = prefs.getBoolean(KEY_AEC, false)
        val source = if (useAec) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.MIC

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, FRAME_BYTES * 4)
        val record = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (_: IllegalArgumentException) {
            failAndStop("AudioRecord 参数无效")
            return
        } catch (_: SecurityException) {
            failAndStop("没有麦克风权限")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            failAndStop("AudioRecord 初始化失败（麦克风被占用？）")
            return
        }
        if (connectionGeneration != generation || captureGeneration != captureId ||
            client !== captureClient) {
            record.release()
            return
        }
        audioRecord = record
        // VOICE_COMMUNICATION 模式下系统会启用硬件级 AEC/NS（设计文档 §6.3）

        captureThread = Thread({
            val frame = ByteArray(FRAME_BYTES)
            try {
                record.startRecording()
            } catch (_: IllegalStateException) {
                failCaptureIfCurrent(
                    "AudioRecord 启动失败（麦克风被占用？）",
                    generation, captureId, captureClient, record,
                )
                record.release()
                return@Thread
            } catch (_: SecurityException) {
                failCaptureIfCurrent("没有麦克风权限", generation, captureId, captureClient, record)
                record.release()
                return@Thread
            }
            while (!Thread.currentThread().isInterrupted &&
                connectionGeneration == generation && captureGeneration == captureId &&
                    audioRecord === record) {
                val n = try {
                    record.read(frame, 0, FRAME_BYTES)
                } catch (_: IllegalStateException) {
                    // 服务停止时会释放 AudioRecord，释放并发发生时在此正常退出。
                    break
                } catch (_: SecurityException) {
                    failCaptureIfCurrent("没有麦克风权限", generation, captureId, captureClient, record)
                    break
                }
                if (n == FRAME_BYTES) {
                    // PTT 门控：未按住时，帧读出即弃（麦克风保持取音，
                    // 数据不出本机；对端掉线时 sendAudio 返回 false，同样不计数）
                    if (connectionGeneration == generation && captureGeneration == captureId &&
                        shouldSend() && captureClient.sendAudio(frame, connectionSerial) &&
                        connectionGeneration == generation && captureGeneration == captureId) {
                        Status.framesSent++
                    }
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

    /** 旧采音线程异常时只允许影响仍属于它的当前连接。 */
    private fun failCaptureIfCurrent(
        message: String,
        generation: Long,
        captureId: Long,
        captureClient: RelayClient,
        record: AudioRecord,
    ) {
        if (connectionGeneration == generation && captureGeneration == captureId &&
            client === captureClient && audioRecord === record) {
            failAndStop(message)
        }
    }

    private fun stopCapture() {
        val t = captureThread
        captureGeneration++
        t?.interrupt()
        releaseAudioRecord()
        waitForThread(t, 1000)
        if (captureThread === t) captureThread = null
    }

    /** 释放录音实例以解除阻塞中的 AudioRecord.read。 */
    private fun releaseAudioRecord() {
        audioRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (_: IllegalStateException) {
            }
        }
        audioRecord = null
    }

    /** 在服务主线程有限等待工作线程退出，避免重启时旧线程占用音频或网络资源。 */
    private fun waitForThread(thread: Thread?, timeoutMs: Long) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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
        const val ACTION_RESTART = "com.remotevoice.app.RESTART"
        const val KEY_USER_STOPPED = "user_stopped"

        // 默认远程服务器（V3.2 部署机），prefs 未配置时回落使用
        const val DEFAULT_SERVER = "118.193.40.160:9432"

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

        /** 用户明确发起重试前清掉上一次错误，避免自动重试覆盖错误原因。 */
        fun prepareRetry() {
            if (Status.state == StatusState.ERROR) {
                Status.state = StatusState.STOPPED
                Status.text = "准备连接…"
                Status.talking = false
            }
        }

        // 仅内部使用
        private const val TAG = "AudioStreamService"
        private const val CHANNEL_ID = "relay_stream"
        private const val NOTIFY_ID = 1
        private const val PREFS = "config"
        private const val KEY_SERVER = "server"
        private const val KEY_AEC = "aec"
    }

}
