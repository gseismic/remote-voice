package com.remotevoice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
enum class StatusState {
    STOPPED, CONNECTING, WAIT_PEER, STREAMING, DISCONNECTED, ERROR;

    fun needsRetry(autoReconnect: Boolean): Boolean =
        this == DISCONNECTED || this == ERROR || (this == WAIT_PEER && !autoReconnect)
}

/**
 * 前台采音服务（协议 v4）：持有 1 条登录连接（服务器状态 + LIST 轮询目录）
 * 与 N 条桥接连接（每台 Mac 一条，v4 target 寻址）。
 * 采音循环单实例：音频/TALK 帧只发给「当前控制目标」[activeDeviceId] 的桥；
 * 切换目标时旧桥发 TALK-off、新桥同步当前 PTT 状态（Mac 端 Fn 不悬空）。
 * 前台服务(microphone 类型)保证后台采音不被系统杀死（设计文档 §6.3）。
 */
class AudioStreamService : Service() {

    private class Bridge(
        val client: RelayClient,
        val thread: Thread,
        var state: StatusState = StatusState.CONNECTING,
        var text: String = "连接 Mac…",
        var peerName: String = "",
    )

    /** 登录和桥接使用同一份服务器配置。换服务器时由完整重启切换。 */
    private var sessionServer: ServerStore.Server? = null

    /** 登录连接（target=""）：服务器连通性标记 + 目录轮询。 */
    private var loginClient: RelayClient? = null
    private var loginThread: Thread? = null

    /** 已建桥的 Mac：deviceId → 连接。主线程（mainHandler 队列）内变更。 */
    private val bridges = LinkedHashMap<String, Bridge>()

    /** 当前控制目标：音频/TALK 只发给它；空=未选。 */
    @Volatile var activeDeviceId: String = ""
        private set

    @Volatile private var captureThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null
    /** 每次采音启停递增，隔离前后两代采音线程。 */
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

        /** 证书与本地记录不一致（PLAN-025）：主界面据此显示「重新信任并重连」按钮。 */
        @Volatile var certChangePending: Boolean = false

        /** 服务器（登录会话）连通性：主界面服务器标记数据源。 */
        @Volatile var serverOnline: Boolean = false
        @Volatile var serverNeedsRetry: Boolean = false
        @Volatile var serverText: String = "未启动"

        /** 当前登录会话收到 LIST 后才可根据目录判断 Mac 是否在线。 */
        @Volatile var macDirectoryLoaded: Boolean = false

        /** 当前控制目标的 device_id（主界面页指示器）。 */
        @Volatile var activeMacId: String = ""

        /** 每台已选择 Mac 的状态。滑动只切换已就绪的桥接。 */
        @Volatile var bridgeStates: Map<String, StatusState> = emptyMap()
        val connectedMacs: List<String>
            get() = bridgeStates.filterValues { it == StatusState.STREAMING }.keys.toList()

        /** Mac 条目或桥接状态变化时递增，主界面据此更新列表。 */
        @Volatile var macsRevision: Long = 0
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
            ACTION_RETRY -> retryConnections()
            ACTION_SET_ACTIVE -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
                if (deviceId.isNotEmpty()) connectBridge(deviceId)
            }
            ACTION_DISCONNECT_MAC -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
                if (deviceId.isNotEmpty()) {
                    disconnectMac(deviceId)
                }
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

    // ---- 生命周期 ----

    private fun startStreaming(startId: Int = activeStartId) {
        if (sessionServer != null) return
        preserveError = false
        Status.certChangePending = false
        activeStartId = startId
        connectionGeneration++
        // 前台服务必须尽早发布通知；即使配置或音频初始化失败，也要让系统知道启动已处理。
        try {
            startForegroundWith("启动中…")
        } catch (_: SecurityException) {
            failAndStop("系统拒绝启动麦克风服务，请检查权限")
            return
        }

        pttHeld = false
        val store = ServerStore(this)
        val server = store.active()
        if (server == null || server.password.isBlank()) {
            failAndStop("尚未配置服务器或密码，请在设置中添加")
            return
        }
        sessionServer = server
        Status.framesSent = 0
        Status.talking = false
        applyState(StatusState.CONNECTING, "连接服务器…")

        // 恢复上次控制目标
        activeDeviceId = store.target(server.id)
        Status.activeMacId = activeDeviceId

        startLogin(server)
        if (activeDeviceId.isNotEmpty()) connectBridge(activeDeviceId)
    }

    private fun startLogin(server: ServerStore.Server) {
        loginClient?.stop()
        loginThread?.interrupt()
        Status.serverOnline = false
        Status.serverNeedsRetry = false
        Status.serverText = "连接中…"
        Status.macDirectoryLoaded = false

        val store = ServerStore(this)
        val generation = connectionGeneration
        lateinit var login: RelayClient
        val loginListener = object : RelayClient.Listener {
            private fun postIfCurrent(action: () -> Unit) {
                mainHandler.post {
                    if (!preserveError && connectionGeneration == generation && loginClient === login) action()
                }
            }

            override fun onState(state: RelayClient.State, text: String) {
                postIfCurrent {
                    Status.serverText = text
                    Status.serverOnline = state == RelayClient.State.AUTHENTICATED
                    Status.serverNeedsRetry = state == RelayClient.State.DISCONNECTED
                    if (!Status.serverOnline) Status.macDirectoryLoaded = false
                    if (activeDeviceId.isEmpty()) updateUnselectedState()
                }
            }

            override fun onPeerName(name: String) {}

            override fun onMacs(macs: List<MacInfo>) {
                postIfCurrent {
                    val directory = macs.sortedBy { it.deviceId }
                    if (store.macCache(server.id) != directory) {
                        store.saveMacCache(directory, server.id)
                        Status.macsRevision++
                    }
                    Status.macDirectoryLoaded = true
                    removeMissingWaitingBridges(directory)
                }
            }

            override fun onFatal(message: String) {
                postIfCurrent { failAndStop(message) }
            }

            override fun onCertChanged() {
                postIfCurrent {
                    Status.certChangePending = true
                    failAndStop(CERT_CHANGED_TEXT)
                }
            }
        }
        login = newRelayClient(server, "", loginListener)
        loginClient = login
        loginThread = Thread({ login.runForever() }, "relay-login").apply { start() }
    }

    private fun retryConnections() {
        val server = sessionServer
        if (preserveError || server == null) {
            stopStreaming()
            startStreaming()
            return
        }
        if (Status.serverNeedsRetry) startLogin(server)
        val bridge = activeBridge()
        if (bridge != null && bridge.state.needsRetry(autoReconnectEnabled())) connectBridge(activeDeviceId)
    }

    private fun stopStreaming() {
        // 先使已经排队的旧回调失效，再关闭底层连接和采音资源。
        connectionGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        stopCapture()
        synchronized(bridges) {
            bridges.values.forEach {
                it.client.stop()
                it.thread.interrupt()
            }
            bridges.clear()
        }
        loginClient?.stop()
        loginClient = null
        val oldLogin = loginThread
        loginThread = null
        oldLogin?.interrupt()
        waitForThread(oldLogin, 1000)
        sessionServer = null
        publishBridgeStates()
        Status.serverOnline = false
        Status.serverNeedsRetry = false
        Status.macDirectoryLoaded = false
        Status.peerName = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- 桥接管理（Activity 通过 Intent 触发，主线程执行）----

    /** 连接或重试一台 Mac。已有可用或正在自动恢复的桥接时只切换目标。 */
    private fun connectBridge(deviceId: String) {
        val server = sessionServer ?: return
        val existing = synchronized(bridges) { bridges[deviceId] }
        if (existing != null) {
            if (!existing.state.needsRetry(autoReconnectEnabled())) {
                setActiveTarget(deviceId)
                return
            }
            removeBridge(deviceId)
        }
        val generation = connectionGeneration
        lateinit var bridge: Bridge
        val listener = object : RelayClient.Listener {
            private fun isCurrent(): Boolean =
                !preserveError && connectionGeneration == generation &&
                    synchronized(bridges) { bridges[deviceId] } === bridge

            private fun postIfCurrent(action: () -> Unit) {
                mainHandler.post {
                    if (isCurrent()) action()
                }
            }

            override fun onState(state: RelayClient.State, text: String) {
                postIfCurrent {
                    val status = when (state) {
                        RelayClient.State.CONNECTING, RelayClient.State.AUTHENTICATED -> StatusState.CONNECTING
                        RelayClient.State.WAIT_PEER -> StatusState.WAIT_PEER
                        RelayClient.State.READY -> StatusState.STREAMING
                        RelayClient.State.DISCONNECTED -> StatusState.DISCONNECTED
                    }
                    updateBridgeState(deviceId, bridge, status, text)
                }
            }

            override fun onPeerName(name: String) {
                postIfCurrent {
                    bridge.peerName = name
                    if (deviceId == activeDeviceId) Status.peerName = name
                    patchMacCache(server.id, deviceId) { it.copy(name = name) }
                }
            }

            override fun onFatal(message: String) {
                postIfCurrent {
                    updateBridgeState(deviceId, bridge, StatusState.ERROR, message)
                }
            }

            override fun onCertChanged() {
                postIfCurrent {
                    Status.certChangePending = true
                    removeBridge(deviceId)
                    failAndStop(CERT_CHANGED_TEXT)
                }
            }
        }
        val relay = newRelayClient(server, deviceId, listener)
        bridge = Bridge(relay, Thread({ relay.runForever() }, "bridge-$deviceId"))
        synchronized(bridges) { bridges[deviceId] = bridge }
        publishBridgeStates()
        setActiveTarget(deviceId)
        bridge.thread.start()
    }

    private fun newRelayClient(server: ServerStore.Server, target: String, listener: RelayClient.Listener): RelayClient {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RelayClient(
            server.host, server.port, secretHashHex(server.password),
            { host, port -> TrustStore.load(prefs, TrustStore.serverKey(host, port)) }, listener,
            server.mode, target, autoReconnectEnabled(),
        )
    }

    private fun autoReconnectEnabled(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_RECONNECT, true)

    /** 切换当前控制目标：旧目标发 TALK-off，新目标同步当前 PTT 状态。 */
    private fun setActiveTarget(deviceId: String) {
        val bridge = synchronized(bridges) { bridges[deviceId] } ?: return
        if (deviceId != activeDeviceId) {
            if (pttHeld) activeBridge()?.client?.sendTalk(false, bridgeSerial(activeDeviceId))
            stopCapture()
            saveActiveTarget(deviceId)
            activeDeviceId = deviceId
            Status.activeMacId = deviceId
            Status.macsRevision++
        }
        publishActiveBridge(bridge)
    }

    private fun updateBridgeState(deviceId: String, bridge: Bridge, state: StatusState, text: String) {
        val changed = bridge.state != state
        bridge.state = state
        bridge.text = text
        if (changed) publishBridgeStates()
        if (deviceId == activeDeviceId) publishActiveBridge(bridge)
    }

    private fun publishActiveBridge(bridge: Bridge) {
        Status.peerName = bridge.peerName
        applyState(bridge.state, bridge.text)
        if (bridge.state == StatusState.STREAMING) startCapture()
        else if (captureThread != null) stopCapture()
    }

    /** 目录包含已登记的离线设备。等待中的 ID 从最新目录消失才撤销选择。 */
    private fun removeMissingWaitingBridges(macs: List<MacInfo>) {
        val registeredIds = macs.mapTo(HashSet()) { it.deviceId }
        val missing = synchronized(bridges) {
            bridges.filter { (id, bridge) -> bridge.state == StatusState.WAIT_PEER && id !in registeredIds }.keys
        }
        missing.forEach(::removeBridge)
        if (activeDeviceId.isNotEmpty() && activeDeviceId !in registeredIds && activeBridge() == null) {
            selectRemainingMac()
        }
    }

    /** 目录中目标消失时回到未选状态（LIST 回调路径）。 */
    private fun clearActiveTarget() {
        stopCapture()
        saveActiveTarget("")
        activeDeviceId = ""
        Status.activeMacId = ""
        Status.peerName = ""
        Status.macsRevision++
        updateUnselectedState()
    }

    /** 扫码可能已保存新目标，服务旧会话只更新自己选中的记录。 */
    private fun saveActiveTarget(deviceId: String) {
        val server = sessionServer ?: return
        val store = ServerStore(this)
        if (store.target(server.id) == activeDeviceId) store.selectMac(deviceId, server.id)
    }

    private fun updateUnselectedState() {
        val state = when {
            Status.serverOnline -> StatusState.WAIT_PEER
            Status.serverNeedsRetry -> StatusState.DISCONNECTED
            else -> StatusState.CONNECTING
        }
        applyState(state, Status.serverText)
    }

    private fun disconnectMac(deviceId: String) {
        val wasActive = deviceId == activeDeviceId
        removeBridge(deviceId)
        if (wasActive) selectRemainingMac()
    }

    private fun selectRemainingMac() {
        val next = Status.connectedMacs.firstOrNull()
        if (next != null) setActiveTarget(next) else clearActiveTarget()
    }

    private fun removeBridge(deviceId: String) {
        val bridge = synchronized(bridges) { bridges.remove(deviceId) } ?: return
        bridge.client.stop()
        bridge.thread.interrupt()
        publishBridgeStates()
    }

    private fun publishBridgeStates() {
        Status.bridgeStates = synchronized(bridges) { bridges.mapValues { it.value.state } }
        Status.macsRevision++
    }

    private fun bridgeSerial(deviceId: String): Long =
        synchronized(bridges) { bridges[deviceId] }?.client?.currentConnectionSerial() ?: -1L

    private fun activeBridge(): Bridge? =
        activeDeviceId.takeIf { it.isNotEmpty() }?.let { id ->
            synchronized(bridges) { bridges[id] }
        }

    // ---- PTT 门控（Activity 线程调用） ----

    /** 按住说话：按下开始传输、松开停止（仅影响发送，不影响连接）。 */
    fun setTalking(on: Boolean) {
        pttHeld = on
        Status.talking = on
        // 计时=按住时长：按下打点、松开清零（render 侧用 startedAt 显示 mm:ss）
        Status.startedAt = if (on) SystemClock.elapsedRealtime() else 0L
        // 只发给当前控制目标；未桥接时丢弃，桥接建立会重发
        activeBridge()?.client?.sendTalk(on, bridgeSerial(activeDeviceId))
        mainHandler.post { updateNotification(if (on) "正在传输…" else Status.text) }
    }

    /** 是否满足发送条件：纯 PTT 语义（V3.2），仅按住时发送。 */
    private fun shouldSend(): Boolean = pttHeld

    // ---- 状态渲染 ----

    private fun applyState(state: StatusState, text: String) {
        Status.state = state
        // 传输计时起点：进入 STREAMING 时不计时（桥接≠说话），按住 PTT 时由
        // setTalking 打点；离开传输态一律清零（主界面 mm:ss 计时=按住时长）
        if (state != StatusState.STREAMING) {
            Status.startedAt = 0L
        }
        Status.text = text
        mainHandler.post { updateNotification(notificationText()) }
    }

    private fun notificationText(): String {
        val name = Status.peerName.ifBlank { "Mac" }
        return when (Status.state) {
            StatusState.STREAMING -> "已就绪 · $name"
            else -> Status.text
        }
    }

    private fun patchMacCache(serverId: String, deviceId: String, patch: (MacInfo) -> MacInfo) {
        val store = ServerStore(this)
        store.saveMacCache(store.macCache(serverId).map { if (it.deviceId == deviceId) patch(it) else it }, serverId)
        Status.macsRevision++
    }

    /** 采音循环：单实例，帧只发给当前控制目标。 */
    private fun startCapture() {
        if (captureThread?.isAlive == true) return
        val generation = connectionGeneration
        val captureId = ++captureGeneration
        // 固定本次采音对应的目标与桥；切换目标后旧线程必须失效
        val targetId = activeDeviceId
        val captureBridge = activeBridge() ?: return
        val connectionSerial = captureBridge.client.currentConnectionSerial()
        // 桥接建立/重连/切换目标后无条件同步当前 PTT 状态（true/false 都发）：
        // 断网/切页期间的 TALK 帧可能丢失（包括"松开"），以手机现状纠偏，
        // 防止 Mac 端 Fn 悬空（设计 §1 时序保证）
        captureBridge.client.sendTalk(pttHeld, connectionSerial)
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
            activeDeviceId != targetId || activeBridge() !== captureBridge) {
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
                    generation, captureId, captureBridge, record,
                )
                record.release()
                return@Thread
            } catch (_: SecurityException) {
                failCaptureIfCurrent("没有麦克风权限", generation, captureId, captureBridge, record)
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
                    failCaptureIfCurrent("没有麦克风权限", generation, captureId, captureBridge, record)
                    break
                }
                if (n == FRAME_BYTES) {
                    // PTT 门控 + 目标路由：帧只发给当前控制目标的桥；
                    // 未按住时帧读出即弃；对端掉线时 sendAudio 返回 false，同样不计数
                    val routed = activeBridge() === captureBridge && activeDeviceId == targetId
                    if (connectionGeneration == generation && captureGeneration == captureId &&
                        shouldSend() && routed &&
                        captureBridge.client.sendAudio(frame, connectionSerial) &&
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

    /** 旧采音线程异常时只允许影响仍属于它的当前桥。 */
    private fun failCaptureIfCurrent(
        message: String,
        generation: Long,
        captureId: Long,
        captureBridge: Bridge,
        record: AudioRecord,
    ) {
        mainHandler.post {
            if (connectionGeneration == generation && captureGeneration == captureId &&
                activeBridge() === captureBridge && audioRecord === record) {
                failAndStop(message)
            }
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

    /** 记录可重试的错误并停止服务；错误状态会留在主界面直到用户主动重试。 */
    private fun failAndStop(message: String) {
        preserveError = true
        val startId = activeStartId
        val text = if (message.startsWith("错误：")) message else "错误：$message"
        Status.state = StatusState.ERROR
        Status.text = text
        Status.talking = false
        // 音频初始化失败或认证失败后立即停止网络重连，避免错误状态被后续连接事件冲掉。
        loginClient?.stop()
        synchronized(bridges) { bridges.values.forEach { it.client.stop() } }
        mainHandler.post {
            if (!preserveError || activeStartId != startId) return@post
            updateNotification(text)
            stopSelfResult(startId)
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
        const val ACTION_RETRY = "com.remotevoice.app.RETRY"
        const val ACTION_SET_ACTIVE = "com.remotevoice.app.SET_ACTIVE"
        const val ACTION_DISCONNECT_MAC = "com.remotevoice.app.DISCONNECT_MAC"
        const val EXTRA_DEVICE_ID = "device_id"
        const val KEY_USER_STOPPED = "user_stopped"

        // 首次手动配置时显示的公网中继，扫码可替换为 Mac 使用的地址。
        const val DEFAULT_SERVER = "https://voice.gpt60.com"

        /** 证书更换（PLAN-025）的统一文案。 */
        const val CERT_CHANGED_TEXT =
            "服务器证书与上次连接不一致。如是你本人更换了证书或重装了服务器，点「重新信任并重连」即可"

        // 音频规格：48kHz/mono/s16le/20ms 帧（设计文档 §4.1，与服务器/接收器一致）
        const val SAMPLE_RATE = 48000
        const val FRAME_BYTES = 1920

        /** 当前服务实例（MainActivity PTT 门控调用）；null=未在运行。 */
        @Volatile var instance: AudioStreamService? = null
            private set

        /** 密码规范化（去空格/连字符+大写）后 SHA-256 hex——传输与 relay 一致。 */
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

        /**
         * 用户点「重新信任并重连」（PLAN-025）：清除该服务器的证书记录后重启连接。
         * 静态方法：证书更换后服务可能已被 stopSelfResult 销毁，仍需可调用。
         */
        fun retrustAndRestart(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val server = ServerStore(context).active()
            if (server != null) {
                TrustStore.clear(prefs, TrustStore.serverKey(server.host, server.port))
            }
            Status.certChangePending = false
            context.startService(
                Intent(context, AudioStreamService::class.java).setAction(ACTION_RESTART)
            )
        }

        // 仅内部使用
        private const val TAG = "AudioStreamService"
        private const val CHANNEL_ID = "relay_stream"
        private const val NOTIFY_ID = 1
        const val PREFS = "config"
        private const val KEY_AEC = "aec"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"

        /** 上次/当前控制目标（服务重启后自动接回）。 */
    }
}
