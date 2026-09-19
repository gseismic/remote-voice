package com.remotevoice.app

import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * relay 线路协议客户端（v3 配对秘密认证）。
 *
 * 帧格式: [1B type][4B length BigEndian][payload]
 * 类型: AUTH=0x01 AUTH_OK=0x02 AUTH_ERR=0x03 AUDIO=0x04 PING=0x05 PONG=0x06 PEER_STATE=0x07
 *
 * v3 认证模型：手机只携带 规范化秘密的 SHA-256 hex（长度固定 64），
 * AUTH_OK 附目标 Mac 设备名（app 用于"首次连接自动命名"）。
 * 当前流程中任何 AUTH_ERR 均视为不可自动恢复（原因码转人性化文案交给 UI）。
 */
class RelayClient(
    private val host: String,
    private val port: Int,
    private val secretHex: String,
    private val fingerprintFor: (host: String, port: Int) -> String,
    private val listener: Listener,
    private val strict: Boolean = false,
) {
    interface Listener {
        /** 状态文本变化（已本地化，可直接展示）。 */
        fun onState(text: String)

        /** 对端名字（AUTH_OK 回传），用于设备条目自动命名。 */
        fun onPeerName(name: String)

        /** 对端上线：开始采音（实际发送受 PTT 门控）。 */
        fun onPeerOnline()

        /** 对端掉线：暂停采音（连接保持）。 */
        fun onPeerOffline()

        /** 致命错误（认证被拒/指纹不符）：不应重试。 */
        fun onFatal(message: String)

        /**
         * TOFU 首次信任：未配置指纹连接时回调实际证书指纹与所属 server key（供上层持久化）。
         * 此后本实例后续连接以该指纹固定校验。
         */
        fun onPeerFingerprint(fingerprint: String, serverKey: String) {}
    }

    // ---- 协议常量（必须与 server/internal/protocol 保持一致；v3）----
    private val frameAuth = 0x01
    private val frameAuthOk = 0x02
    private val frameAuthErr = 0x03
    private val frameAudio = 0x04
    private val framePing = 0x05
    private val framePong = 0x06
    private val framePeerState = 0x07
    private val frameTalk = 0x0A
    private val peerOnlineByte = 0x01
    private val talkOnByte: Byte = 0x01

    private val maxPayload = 65536
    private val protoVersion = 3
    private val rolePhone = "phone"

    private val pingIntervalMs = 10_000L   // NAT 保活 + 活性探测（设计文档 §4.3）
    private val readTimeoutMs = 40_000     // 超时判定半开连接
    private val backoffMaxMs = 30_000L     // 重连退避封顶

    // 在构造后即进入可运行态，避免 stop() 先于网络线程启动时被 runForever() 覆盖。
    @Volatile private var running = true
    @Volatile private var peerOnline = false
    /** 每次建立新 TCP/TLS 连接递增，阻止旧采音线程写入重连后的新连接。 */
    @Volatile private var connectionSerial = 0L
    @Volatile var peerName: String = ""
        private set
    @Volatile private var socket: SSLSocket? = null
    /** TCP 拨号期间也登记原始 socket，停止服务时可立即打断阻塞的 connect。 */
    private var connectingSocket: Socket? = null
    private val outLock = Any()
    /**
     * TALK 帧写线程：sendTalk 会被 Activity 主线程（PTT 回调）和服务主线程
     * （startCapture 的桥接同步）调用，TLS 写绝不能留在主线程（NetworkOnMainThreadException
     * 必崩，PLAN-022）；单线程 FIFO 保证 TALK on/off 顺序稳定，stop() 时关闭。
     */
    private val talkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "relay-talk").apply { isDaemon = true }
    }
    // TOFU/固定指纹（key = TrustStore.serverKey）
    private val trusted = HashMap<String, String>()

    init {
        if (!strict) {
            val known = fingerprintFor(host, port).trim().lowercase()
            if (known.isNotEmpty()) trusted[serverKey()] = known
        }
    }

    private fun serverKey(): String = TrustStore.serverKey(host, port)

    /**
     * 连接主循环：连接 → 认证 → 读事件 → 断线退避重连。
     * 在专用线程调用，阻塞直至 [stop]。
     */
    fun runForever() {
        var attempts = 0
        while (running) {
            connectionSerial++
            try {
                listener.onState(if (attempts == 0) "连接中…" else "重连中(第${attempts}次)…")
                connectAndServe()
                attempts = 0 // 正常退出（stop）时归零
            } catch (fatal: FatalProtocolError) {
                Log.w(TAG, "fatal: ${fatal.message}")
                listener.onFatal(fatal.message ?: "协议错误")
                running = false
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "connection lost", e)
                val why = describeError(e)
                listener.onState(if (attempts == 0) "连接失败: $why" else "重连失败($attempts): $why")
            } finally {
                val wasPeerOnline = peerOnline
                closeQuietly()
                peerOnline = false
                if (wasPeerOnline) listener.onPeerOffline()
            }
            if (!running) break
            attempts++
            val delay = minOf(1000L shl minOf(attempts - 1, 5), backoffMaxMs)
            listener.onState("断开，${delay / 1000}秒后重连(第$attempts 次)")
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                break
            }
        }
        listener.onState("已停止")
    }

    fun stop() {
        running = false
        // 关闭 TALK 写线程；此后 sendTalk 提交会走 RejectedExecutionException 分支
        talkExecutor.shutdownNow()
        closeQuietly()
    }

    /** 由采音线程调用；仅在桥接就绪后真正发送。 */
    fun sendAudio(payload: ByteArray, expectedConnectionSerial: Long): Boolean {
        // 先固定 socket；否则检查通过后若恰好发生自动重连，sendFrame 可能拿到新 socket。
        val currentSocket = socket ?: return false
        if (!peerOnline || connectionSerial != expectedConnectionSerial) return false
        return try {
            sendFrameTo(currentSocket.getOutputStream(), frameAudio, payload)
        } catch (e: IOException) {
            Log.w(TAG, "send audio failed", e)
            false
        }
    }

    /** 返回当前 TLS 会话标识，采音线程用它隔离网络自动重连。 */
    fun currentConnectionSerial(): Long = connectionSerial

    /**
     * 发送说话状态（PTT 按下/松开，FRAME_TALK 1 字节）。
     * best-effort：桥接未就绪或串号时丢弃，不重试——下一条状态会覆盖旧状态。
     * 实际 TLS 写提交到 talkExecutor（主线程调用安全），FIFO 保证 on/off 顺序。
     */
    fun sendTalk(on: Boolean, expectedConnectionSerial: Long): Boolean {
        val currentSocket = socket ?: return false
        if (!running || !peerOnline || connectionSerial != expectedConnectionSerial) return false
        return try {
            talkExecutor.execute {
                // 任务内自捕获：socket 关闭竞态下 getOutputStream/write 抛 IOException，
                // 不能让执行器线程出现未捕获异常（同样会杀进程）
                try {
                    sendFrameTo(
                        currentSocket.getOutputStream(), frameTalk,
                        byteArrayOf(if (on) talkOnByte else 0x00),
                    )
                } catch (e: IOException) {
                    Log.w(TAG, "send talk failed", e)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            // stop() 已关闭执行器（服务停止竞态）：丢弃即可
            false
        }
    }

    private fun connectAndServe() {
        connectOne()
    }

    private fun connectOne() {
        val ctx = if (strict) {
            // rvs:// 标准 TLS：系统 CA 信任 + 握手后主机名校验（设计 tls-ca-mode §4.3）；
            // 绝不进入 TrustAll/TOFU 分支
            SSLContext.getInstance("TLS").apply { init(null, null, null) }
        } else {
            val serverKey = serverKey()
            val knownFingerprint = trusted[serverKey].orEmpty()
            // 已知指纹=固定校验；空=TOFU（首次连接自动信任并记录）
            val tm = if (knownFingerprint.isNotEmpty()) {
                FingerprintTrustManager(knownFingerprint)
            } else {
                TrustAllTrustManager()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        }
        listener.onState("连接 $host…")

        val raw = Socket()
        var connectedSocket: SSLSocket? = null
        var handedToTls = false
        synchronized(this) { connectingSocket = raw }
        try {
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            if (!running) throw IOException("客户端已停止")
            val sock = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            handedToTls = true
            synchronized(this) {
                connectingSocket = null
                if (!running) {
                    sock.close()
                    throw IOException("客户端已停止")
                }
                socket = sock
            }
            connectedSocket = sock
            sock.soTimeout = readTimeoutMs
            sock.tcpNoDelay = true
            try {
                sock.startHandshake()
            } catch (e2: SSLHandshakeException) {
                var cause: Throwable? = e2
                while (cause != null) {
                    val message = cause.message.orEmpty()
                    if (message.contains("证书指纹不符")) {
                        throw FatalProtocolError(message)
                    }
                    cause = cause.cause
                }
                throw e2
            }
            // 裸 SSLSocket 不做主机名校验，rvs:// 必须显式补上（设计 tls-ca-mode §7）
            if (strict && !HttpsURLConnection.getDefaultHostnameVerifier()
                    .verify(host, sock.session)
            ) {
                throw SSLHandshakeException("服务器证书与主机名 $host 不匹配")
            }
        } finally {
            synchronized(this) {
                if (connectingSocket === raw) connectingSocket = null
            }
            if (!handedToTls) {
                try {
                    raw.close()
                } catch (_: IOException) {
                }
            }
        }
        val sock = connectedSocket ?: throw IOException("TLS 连接未建立")
        if (!strict && (trusted[serverKey()] ?: "").isEmpty()) {
            // TOFU：握手后记录实际证书指纹，本 endpoint 后续重连以之校验（rvs:// 不做）
            val chain = sock.session.peerCertificates
            val fp = FingerprintTrustManager.fingerprintOf((chain[0] as X509Certificate).encoded)
            trusted[serverKey()] = fp
            listener.onPeerFingerprint(fp, serverKey())
            Log.i(TAG, "tofu trusted fingerprint=$fp")
        }
        Log.i(TAG, "tls connected ($host)")

        // AUTH 必须是首帧（服务器 10s 内等待）：v3 手机只带秘密哈希
        sock.soTimeout = AUTH_TIMEOUT_MS
        val authJson = "{\"role\":\"$rolePhone\",\"secret\":\"${jsonEscape(secretHex)}\",\"proto\":$protoVersion}"
        sendFrame(frameAuth, authJson.toByteArray(Charsets.UTF_8))
        listener.onState("认证中…")

        val input = DataInputStream(sock.getInputStream().buffered())
        val out = sock.getOutputStream()

        var header = ByteArray(5)
        input.readFully(header)
        var type = header[0].toInt() and 0xFF
        var len = readLength(header)
        var payload = ByteArray(len)
        input.readFully(payload)
        when (type) {
            frameAuthOk -> {
                // v3：AUTH_OK 携带 Mac 设备名（自动命名数据源）
                peerName = try {
                    JSONObject(String(payload, Charsets.UTF_8)).optString("mac", "")
                } catch (_: Exception) {
                    ""
                }
                listener.onPeerName(peerName)
                Log.i(TAG, "auth ok, peer=$peerName")
            }
            frameAuthErr -> throw FatalProtocolError(reasonText(String(payload, Charsets.UTF_8)))
            else -> throw FatalProtocolError("认证应答异常 type=0x%02x".format(type))
        }

        listener.onState(if (peerName.isBlank()) "已连接，等待 Mac 上线…" else "已连接 · $peerName")
        peerOnline = false
        sock.soTimeout = readTimeoutMs

        // 心跳线程：周期 PING 保活
        val heartbeat = Thread {
            while (running && !sock.isClosed) {
                try {
                    Thread.sleep(pingIntervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                sendFrameTo(out, framePing, ByteArray(0))
            }
        }.apply { isDaemon = true; name = "relay-heartbeat"; start() }

        try {
            header = ByteArray(5)
            while (running) {
                input.readFully(header)
                type = header[0].toInt() and 0xFF
                len = readLength(header)
                payload = ByteArray(len)
                input.readFully(payload) // 未知类型也必须消费 payload 以保持帧同步
                when (type) {
                    framePing -> sendFrameTo(out, framePong, ByteArray(0))
                    frameAudio -> {} // 手机端不接收音频
                    framePeerState -> handlePeerState(payload)
                    // AUTH/AUTH_OK/AUTH_ERR 及未知类型：忽略（向前兼容）
                }
            }
        } finally {
            heartbeat.interrupt()
        }
    }

    private fun handlePeerState(payload: ByteArray) {
        if (payload.isEmpty()) return
        if (payload[0].toInt() == peerOnlineByte) {
            peerOnline = true
            // 桥接建立 ≠ 正在推流：未按住 PTT 前不出声，文案避免误导
            listener.onState(if (peerName.isBlank()) "已就绪" else "已就绪 · $peerName")
            listener.onPeerOnline()
        } else {
            peerOnline = false
            listener.onState(if (peerName.isBlank()) "已连接服务器，等待 Mac 上线…" else "已连接 · $peerName")
            listener.onPeerOffline()
        }
    }

    /** AUTH_ERR 原因码 → 人性化文案。 */
    private fun reasonText(reason: String): String = when (reason) {
        "invalid-secret" -> "未找到匹配设备（该 Mac 未在线或密码已更新）"
        "secret-expired" -> "临时密码已过期，请在 Mac 端更新或删除该设备后重试"
        "peer-busy" -> "目标 Mac 正在其他会话中，稍后再试"
        "rate-limited" -> "尝试过于频繁，已被临时锁定（1 分钟后再试）"
        else -> "认证被拒（$reason）"
    }

    private fun readLength(header: ByteArray): Int {
        val len = ((header[1].toLong() and 0xFF) shl 24) or
            ((header[2].toLong() and 0xFF) shl 16) or
            ((header[3].toLong() and 0xFF) shl 8) or
            (header[4].toLong() and 0xFF)
        if (len > maxPayload) throw IOException("帧长超限: $len")
        return len.toInt()
    }

    private fun sendFrame(type: Int, payload: ByteArray): Boolean {
        val s = socket ?: return false
        return try {
            sendFrameTo(s.getOutputStream(), type, payload)
        } catch (e: IOException) {
            Log.w(TAG, "send failed", e)
            false
        }
    }

    private fun sendFrameTo(out: OutputStream, type: Int, payload: ByteArray): Boolean =
        try {
            synchronized(outLock) {
                out.write(
                    byteArrayOf(
                        type.toByte(),
                        ((payload.size shr 24) and 0xFF).toByte(),
                        ((payload.size shr 16) and 0xFF).toByte(),
                        ((payload.size shr 8) and 0xFF).toByte(),
                        (payload.size and 0xFF).toByte(),
                    )
                )
                out.write(payload)
                out.flush()
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "sendFrameTo failed", e)
            false
        }

    private fun closeQuietly() {
        val tls: SSLSocket?
        val raw: Socket?
        synchronized(this) {
            tls = socket
            socket = null
            raw = connectingSocket
            connectingSocket = null
        }
        try {
            tls?.close()
        } catch (_: IOException) {
        }
        try {
            raw?.close()
        } catch (_: IOException) {
        }
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else ->
                if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    /** 不应自动重试的协议级错误（认证/指纹类）。 */
    private class FatalProtocolError(message: String) : RuntimeException(message)

    /** TOFU 专用：指纹未配置时信任任意证书，随后在应用层记录并固定校验。 */
    private class TrustAllTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** 异常 → 排障可读文案（供状态条/通知直接展示）。 */
    private fun describeError(e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message ?: ""
            if (m.contains("指纹不符")) return m.take(120)
            t = t.cause
        }
        return when (e) {
            is SocketTimeoutException -> "连接超时（服务器未响应或防火墙拦了端口）"
            is UnknownHostException -> "域名解析失败（检查设置中的服务器地址）"
            is ConnectException -> "连接被拒绝（服务器未启动？端口未放行？）"
            is SSLHandshakeException -> "TLS 握手失败（证书问题或版本不匹配）"
            is IOException -> "网络错误: ${e.message ?: "读写失败"}"
            else -> "${e.javaClass.simpleName}: ${e.message ?: ""}"
        }.take(120)
    }

    private companion object {
        const val TAG = "RelayClient"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val AUTH_TIMEOUT_MS = 10_000
    }
}
