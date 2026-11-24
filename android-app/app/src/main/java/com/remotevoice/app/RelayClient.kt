package com.remotevoice.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal const val MAC_START_HINT = "请在 Mac 上打开 Remote Voice，并确认已连接中继。"

/**
 * 中继线路协议客户端（v4 服务器密码认证）。
 *
 * 帧格式: [1B type][4B length BigEndian][payload]
 * 类型: AUTH=0x01 AUTH_OK=0x02 AUTH_ERR=0x03 AUDIO=0x04 PING=0x05 PONG=0x06 PEER_STATE=0x07
 *
 * 手机携带服务器密码的 SHA-256 十六进制值，建桥时 AUTH_OK 附目标 Mac 设备名。
 * 启用自动重连时，等待离线的目标 Mac 恢复后重新建桥。
 *
 * TLS 策略（PLAN-025）：AUTO 先标准 CA+主机名验证，仅证书验证失败回落 TOFU
 * （服务器换真证书自动升级）；STRICT 只标准验证；TOFU 只 TOFU。
 * TOFU 指纹不符 = 可恢复的 onCertChanged（UI 提供"重新信任"入口），绝不报死局错误。
 */
class RelayClient(
    private val host: String,
    private val port: Int,
    private val secretHex: String,
    private val fingerprintFor: (host: String, port: Int) -> String,
    private val listener: Listener,
    private val mode: ConfigParser.TlsMode = ConfigParser.TlsMode.AUTO,
    /** v4：目标 Mac 的 device_id；空=登录会话（仅 LIST/状态，不建桥）。 */
    private val target: String = "",
    /** 断线自动重连开关（false 时网络错误即停，等用户手动重试）。 */
    private val autoReconnect: Boolean = true,
) {
    enum class State { CONNECTING, AUTHENTICATED, WAIT_PEER, READY, DISCONNECTED }

    interface Listener {
        /** 连接状态及可显示的说明。 */
        fun onState(state: State, text: String)

        /** 对端名字（AUTH_OK 回传），用于设备条目自动命名。 */
        fun onPeerName(name: String)

        /** 本次连接被拒绝，等待用户处理或手动重试。 */
        fun onFatal(message: String)

        /**
         * TOFU 首次信任：未配置指纹连接时回调实际证书指纹与所属 server key（供上层持久化）。
         * 此后本实例后续连接以该指纹固定校验。
         */
        fun onPeerFingerprint(fingerprint: String, serverKey: String) {}

        /** 服务器证书与本地记录不一致（PLAN-025）：停止重连，等待用户选择重新信任。 */
        fun onCertChanged() {}

        /** 目录应答（v4 LIST）：登录/桥接会话均可查询。 */
        fun onMacs(macs: List<MacInfo>) {}
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
    private val frameList = 0x0B
    private val peerOnlineByte = 0x01
    private val peerOfflineByte = 0x00
    private val talkOnByte: Byte = 0x01

    private val maxPayload = 65536
    private val protoVersion = 4
    private val rolePhone = "phone"

    private val pingIntervalMs = 10_000L   // NAT 保活 + 活性探测（设计文档 §4.3）
    private val listIntervalMs = 5_000L   // 登录会话的目录查询同时用于保活
    private val readTimeoutMs = 40_000     // 超时判定半开连接
    private val backoffMaxMs = 30_000L     // 重连退避封顶
    private val macBackoffMaxMs = 5_000L  // 等待目标 Mac 时保持较短的恢复时间

    private var waitingForMac = false    // 仅网络线程访问

    // 在构造后即进入可运行态，避免 stop() 先于网络线程启动时被 runForever() 覆盖。
    @Volatile private var running = true
    @Volatile private var peerOnline = false
    /** 每次建立新 TCP/TLS 连接递增，阻止旧采音线程写入重连后的新连接。 */
    @Volatile private var connectionSerial = 0L
    @Volatile var peerName: String = ""
        private set
    @Volatile private var socket: SSLSocket? = null
    /** 从 TCP 拨号到 TLS 会话结束保留原始 socket，停止时用它打断网络线程。 */
    private var transportSocket: Socket? = null
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
        if (mode == ConfigParser.TlsMode.AUTO || mode == ConfigParser.TlsMode.TOFU) {
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
            val failure = try {
                reportConnecting(if (attempts == 0) "连接中…" else "重连中(第${attempts}次)…")
                connectOne()
                null
            } catch (e: Exception) {
                e
            } finally {
                if (peerOnline) attempts = 0
                closeQuietly()
            }
            if (!running || failure == null) break
            when (failure) {
                is CertChangedException -> {
                    Log.w(TAG, "cert changed: ${failure.message}")
                    listener.onCertChanged()
                    break
                }
                is FatalProtocolError -> {
                    Log.w(TAG, "fatal: ${failure.message}")
                    listener.onFatal(failure.message ?: "协议错误")
                    break
                }
                is TargetOfflineException -> {
                    waitingForMac = true
                    listener.onState(State.WAIT_PEER, "等待 Mac 上线…")
                }
                else -> {
                    waitingForMac = false
                    Log.w(TAG, "connection lost", failure)
                    val why = describeError(failure)
                    val text = if (attempts == 0) "连接失败：$why" else "重连失败($attempts)：$why"
                    listener.onState(if (autoReconnect) State.CONNECTING else State.DISCONNECTED, text)
                }
            }
            if (!running || !autoReconnect) break
            attempts++
            val maxDelay = if (waitingForMac) macBackoffMaxMs else backoffMaxMs
            val delay = minOf(1000L shl minOf(attempts - 1, 5), maxDelay)
            reportConnecting("断开，${delay / 1000}秒后重连(第$attempts 次)")
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                break
            }
        }
        stop()
    }

    private fun reportConnecting(text: String) {
        if (!waitingForMac) listener.onState(State.CONNECTING, text)
    }

    fun stop() {
        running = false
        // 关闭 TALK 写线程；此后 sendTalk 提交会走 RejectedExecutionException 分支
        talkExecutor.shutdownNow()
        // TLS close 可能发送 close_notify。主线程只关闭底层 TCP，TLS 由网络线程收尾。
        val raw = synchronized(this) { transportSocket }
        try {
            raw?.close()
        } catch (_: IOException) {
        }
    }

    /** 由采音线程调用；仅在桥接就绪后真正发送。 */
    fun sendAudio(payload: ByteArray, expectedConnectionSerial: Long): Boolean {
        // 先固定 socket；否则检查通过后若恰好发生自动重连，sendFrame 可能拿到新 socket。
        val currentSocket = socket ?: return false
        if (!running || !peerOnline || connectionSerial != expectedConnectionSerial) return false
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

    private fun connectOne() {
        val (sock, standardVerified) = openTlsConnection()
        if (!standardVerified && (trusted[serverKey()] ?: "").isEmpty()) {
            // TOFU：握手后记录实际证书指纹，本 endpoint 后续重连以之校验（标准验证通过时不做）
            val chain = sock.session.peerCertificates
            val fp = FingerprintTrustManager.fingerprintOf((chain[0] as X509Certificate).encoded)
            trusted[serverKey()] = fp
            listener.onPeerFingerprint(fp, serverKey())
            Log.i(TAG, "tofu trusted fingerprint=$fp")
        }
        Log.i(TAG, "tls connected ($host, standard=$standardVerified)")

        val input = DataInputStream(sock.getInputStream().buffered())
        val out = sock.getOutputStream()
        if (mode == ConfigParser.TlsMode.HTTPS) {
            sock.soTimeout = CONNECT_TIMEOUT_MS
            upgradeHttps(input, out)
        }

        // AUTH 是升级完成后的首个数据帧，手机只带服务器密码哈希。
        sock.soTimeout = AUTH_TIMEOUT_MS
        val targetPart = if (target.isBlank()) "" else ",\"target\":\"${jsonEscape(target)}\""
        val authJson =
            "{\"role\":\"$rolePhone\",\"secret\":\"${jsonEscape(secretHex)}\",\"proto\":$protoVersion$targetPart}"
        sendFrame(frameAuth, authJson.toByteArray(Charsets.UTF_8))
        reportConnecting("认证中…")

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
            frameAuthErr -> {
                val reason = String(payload, Charsets.UTF_8)
                if (target.isNotEmpty() && reason == "invalid-target") throw TargetOfflineException()
                throw FatalProtocolError(reasonText(reason))
            }
            else -> throw FatalProtocolError("认证应答异常 type=0x%02x".format(type))
        }

        if (!waitingForMac) {
            listener.onState(State.AUTHENTICATED, if (peerName.isBlank()) "已连接服务器" else "已连接 · $peerName")
        }
        peerOnline = false
        sock.soTimeout = readTimeoutMs

        // 目录请求在认证后从网络线程发出，登录会话用 LIST 保活，桥接会话用 PING。
        val pollingMacs = target.isEmpty()
        val keepAliveFrame = if (pollingMacs) frameList else framePing
        val keepAliveInterval = if (pollingMacs) listIntervalMs else pingIntervalMs
        if (pollingMacs) sendFrameTo(out, frameList, ByteArray(0))
        val heartbeat = Thread {
            while (running && !sock.isClosed) {
                try {
                    Thread.sleep(keepAliveInterval)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                sendFrameTo(out, keepAliveFrame, ByteArray(0))
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
                    frameList -> handleList(payload)
                    // AUTH/AUTH_OK/AUTH_ERR 及未知类型：忽略（向前兼容）
                }
            }
        } finally {
            heartbeat.interrupt()
        }
    }

    /** 完成 HTTP/1.1 中继升级，复用输入缓冲区继续读取数据帧。 */
    private fun upgradeHttps(input: DataInputStream, out: OutputStream) {
        val authority = "${ConfigParser.formatHost(host)}:$port"
        val request = "GET /relay HTTP/1.1\r\nHost: $authority\r\n" +
            "Connection: Upgrade\r\nUpgrade: $RELAY_UPGRADE\r\n\r\n"
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()

        val headers = ByteArray(8192)
        val deadline = System.nanoTime() + CONNECT_TIMEOUT_MS * 1_000_000L
        var length = 0
        while (length < headers.size) {
            if (System.nanoTime() - deadline >= 0) throw SocketTimeoutException("HTTPS 中继握手超时")
            headers[length++] = input.readByte()
            if (length < 4 || headers[length - 4] != '\r'.code.toByte() ||
                headers[length - 3] != '\n'.code.toByte() || headers[length - 2] != '\r'.code.toByte() ||
                headers[length - 1] != '\n'.code.toByte()) continue

            val lines = String(headers, 0, length, Charsets.ISO_8859_1).split("\r\n")
            val status = lines.first().split(' ', limit = 3)
            val switching = status.firstOrNull() == "HTTP/1.1" && status.getOrNull(1) == "101"
            val connection = lines.drop(1).any { line ->
                line.substringBefore(':').equals("Connection", ignoreCase = true) &&
                    line.substringAfter(':', "").split(',').any { it.trim().equals("Upgrade", ignoreCase = true) }
            }
            val upgrade = lines.drop(1).any { line ->
                line.substringBefore(':').equals("Upgrade", ignoreCase = true) &&
                    line.substringAfter(':', "").trim().equals(RELAY_UPGRADE, ignoreCase = true)
            }
            if (!switching || !connection || !upgrade) throw IOException("服务器未接受中继协议升级")
            return
        }
        throw IOException("HTTPS 中继响应头过长")
    }

    /**
     * 建立 TLS 连接并判定是否通过了标准验证。
     * AUTO：先标准 CA+主机名验证，仅证书验证失败才重拨回落 TOFU（服务器换真证书自动升级）；
     * STRICT：只标准验证；TOFU：只 TOFU。指纹不符抛 [CertChangedException]。
     */
    private fun openTlsConnection(): Pair<SSLSocket, Boolean> {
        return when (mode) {
            ConfigParser.TlsMode.TOFU -> dialTls(strictTls = false) to false
            ConfigParser.TlsMode.STRICT, ConfigParser.TlsMode.HTTPS -> dialTls(strictTls = true) to true
            ConfigParser.TlsMode.AUTO -> try {
                dialTls(strictTls = true) to true
            } catch (changed: CertChangedException) {
                throw changed
            } catch (e: SSLHandshakeException) {
                if (isCertificateVerifyError(e)) dialTls(strictTls = false) to false else throw e
            }
        }
    }

    /** 拨号 + TLS 握手。strictTls=true 走系统 CA 信任；false 走 TOFU（指纹固定/信任任意）。 */
    private fun dialTls(strictTls: Boolean): SSLSocket {
        val ctx = if (strictTls) {
            // 标准 TLS 使用系统 CA，主机名随握手验证。
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
        reportConnecting("连接 $host…")

        val raw = Socket()
        var handedToTls = false
        try {
            synchronized(this) {
                if (!running) throw IOException("客户端已停止")
                transportSocket = raw
            }
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            if (!running) throw IOException("客户端已停止")
            val sock = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            handedToTls = true
            synchronized(this) { socket = sock }
            if (!running) throw IOException("客户端已停止")
            sock.soTimeout = CONNECT_TIMEOUT_MS
            sock.tcpNoDelay = true
            if (strictTls) {
                sock.sslParameters = sock.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            }
            try {
                sock.startHandshake()
            } catch (e: SSLHandshakeException) {
                closeSslQuietly(sock)
                if (causeChainContains(e, FingerprintTrustManager.CERT_CHANGED_MESSAGE)) {
                    throw CertChangedException()
                }
                throw e
            }
            return sock
        } finally {
            if (!handedToTls) {
                synchronized(this) {
                    if (transportSocket === raw) transportSocket = null
                }
                try {
                    raw.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun closeSslQuietly(sock: SSLSocket) {
        synchronized(this) {
            if (socket === sock) socket = null
        }
        try {
            sock.close()
        } catch (_: IOException) {
        }
    }

    /** 证书验证类失败判定（自签/未知 CA/主机名不符等）：AUTO 回落 TOFU 的唯一信号。 */
    private fun isCertificateVerifyError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is CertificateException) return true
            val m = (t.message ?: "").lowercase()
            if (m.contains("trust anchor") || m.contains("certpathvalidator") ||
                m.contains("certificateException".lowercase()) || m.contains("certific") ||
                m.contains("主机名")
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }

    private fun causeChainContains(e: Throwable, needle: String): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if ((t.message ?: "").contains(needle)) return true
            t = t.cause
        }
        return false
    }

    /** 服务器证书与本地记录不一致（可恢复；UI 提供重新信任入口，PLAN-025）。 */
    private class CertChangedException :
        IOException(FingerprintTrustManager.CERT_CHANGED_MESSAGE)

    private class TargetOfflineException : IOException()

    private fun handlePeerState(payload: ByteArray) {
        if (target.isEmpty() || payload.isEmpty()) return
        when (payload[0].toInt()) {
            peerOnlineByte -> {
                waitingForMac = false
                peerOnline = true
                // 桥接建立 ≠ 正在推流：未按住 PTT 前不出声，文案避免误导
                listener.onState(State.READY, if (peerName.isBlank()) "已就绪" else "已就绪 · $peerName")
            }
            peerOfflineByte -> {
                // 中继已解除旧桥接。Mac 再上线时，需要用新连接认证并建桥。
                throw TargetOfflineException()
            }
        }
    }

    /** 目录应答（v4 LIST）包含 macs 数组，同时兼容旧的裸数组载荷。 */
    private fun handleList(payload: ByteArray) {
        val macs = try {
            val arr = when (val value = JSONTokener(String(payload, Charsets.UTF_8)).nextValue()) {
                is JSONObject -> value.getJSONArray("macs")
                is JSONArray -> value
                else -> return
            }
            List(arr.length()) { MacInfo.fromJson(arr.getJSONObject(it)) }
        } catch (_: JSONException) {
            return
        }
        listener.onMacs(macs)
    }

    /** AUTH_ERR 原因码 → 人性化文案（v4）。 */
    private fun reasonText(reason: String): String = when (reason) {
        "invalid-secret" -> "服务器密码错误（也可能服务器尚未设置密码）"
        "invalid-target" -> "目标 Mac 离线。$MAC_START_HINT"
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
            raw = transportSocket
            transportSocket = null
        }
        peerOnline = false
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
        const val RELAY_UPGRADE = "remote-voice"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val AUTH_TIMEOUT_MS = 10_000
    }
}
