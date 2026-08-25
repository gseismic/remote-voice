package com.remotevoice.app

import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * relay 线路协议客户端（v2 注册制）。
 *
 * 帧格式: [1B type][4B length BigEndian][payload]
 * 类型: AUTH=0x01 AUTH_OK=0x02 AUTH_ERR=0x03 AUDIO=0x04 PING=0x05 PONG=0x06 PEER_STATE=0x07
 *
 * v2 认证模型：手机只携带 规范化秘密的 SHA-256 hex（长度固定 64），
 * AUTH_OK 附目标 Mac 设备名（app 用于"首次连接自动命名"）。
 * 任何 AUTH_ERR 均视为不可自动恢复（原因码转人性化文案交给 UI）。
 */
class RelayClient(
    private val host: String,
    private val port: Int,
    private val secretHex: String,
    private val fingerprintHex: String,
    private val listener: Listener,
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
    }

    // ---- 协议常量（必须与 relay/internal/protocol 保持一致；v2）----
    private val frameAuth = 0x01
    private val frameAuthOk = 0x02
    private val frameAuthErr = 0x03
    private val frameAudio = 0x04
    private val framePing = 0x05
    private val framePong = 0x06
    private val framePeerState = 0x07
    private val peerOnlineByte = 0x01

    private val maxPayload = 65536
    private val protoVersion = 2
    private val rolePhone = "phone"

    private val pingIntervalMs = 10_000L   // NAT 保活 + 活性探测（设计文档 §4.3）
    private val readTimeoutMs = 40_000     // 超时判定半开连接
    private val backoffMaxMs = 30_000L     // 重连退避封顶

    @Volatile private var running = false
    @Volatile private var peerOnline = false
    @Volatile var peerName: String = ""
        private set
    private var socket: SSLSocket? = null
    private val outLock = Any()

    /**
     * 连接主循环：连接 → 认证 → 读事件 → 断线退避重连。
     * 在专用线程调用，阻塞直至 [stop]。
     */
    fun runForever() {
        running = true
        var attempts = 0
        while (running) {
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
            } finally {
                closeQuietly()
                peerOnline = false
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
        closeQuietly()
    }

    /** 由采音线程调用；仅在桥接就绪后真正发送。 */
    fun sendAudio(payload: ByteArray): Boolean {
        if (!peerOnline) return false
        return sendFrame(frameAudio, payload)
    }

    private fun connectAndServe() {
        val tm = FingerprintTrustManager(fingerprintHex)
        val ctx = SSLContext.getInstance("TLS")
        // 信任根来自指纹而非 CA：跳过默认信任链，由 FingerprintTrustManager 校验
        ctx.init(null, arrayOf(tm), null)

        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), readTimeoutMs)
        val sock = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        synchronized(this) { socket = sock }
        sock.soTimeout = readTimeoutMs
        sock.tcpNoDelay = true
        Log.i(TAG, "tls connected")

        // AUTH 必须是首帧（服务器 10s 内等待）：v2 手机只带秘密哈希
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
                // v2：AUTH_OK 携带 Mac 设备名（自动命名数据源）
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
            listener.onState(if (peerName.isBlank()) "推流中" else "传输中 · $peerName")
            listener.onPeerOnline()
        } else {
            peerOnline = false
            listener.onState(if (peerName.isBlank()) "已连接服务器，等待 Mac 上线…" else "已连接 · $peerName")
            listener.onPeerOffline()
        }
    }

    /** AUTH_ERR 原因码 → 人性化文案（v2 枚举）。 */
    private fun reasonText(reason: String): String = when (reason) {
        "invalid-secret" -> "未找到匹配设备（该 Mac 未在线或秘密已更新）"
        "secret-expired" -> "临时秘密已过期，请在 Mac 端更新或删除该设备后重试"
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
        try {
            synchronized(this) { socket?.close(); socket = null }
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

    private companion object {
        const val TAG = "RelayClient"
        const val AUTH_TIMEOUT_MS = 10_000
    }
}
