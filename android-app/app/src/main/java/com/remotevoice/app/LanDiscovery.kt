package com.remotevoice.app

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * 局域网服务器发现（设计：lan-discovery-dual-address §3）：
 * 向本机所在 IPv4 子网的广播地址发送探测串，收集 server 的 JSON 应答。
 * 应答只含显示名与 TCP 端口；广播失败或无结果时由调用方回落手动输入。
 */
object LanDiscovery {
    /** 探测串，必须与 server/internal/discovery 的 ProbeMessage 完全一致。 */
    const val PROBE_MESSAGE = "RV-DISCOVER-v1"

    data class Found(val host: String, val port: Int, val name: String)

    /**
     * 同步扫描，耗时约 [timeoutMs]；必须在后台线程调用（内部阻塞收包）。
     * 返回按 IP 去重的发现列表，顺序为应答到达顺序。
     */
    fun scan(timeoutMs: Long = 1500L, probePort: Int = 9432): List<Found> {
        val found = LinkedHashMap<String, Found>()
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 150
            val buf = ByteArray(1024)
            val deadline = System.currentTimeMillis() + timeoutMs
            val targets = broadcastAddresses()
            var round = 0
            while (System.currentTimeMillis() < deadline) {
                if (round < 2) {
                    for (t in targets) {
                        val data = PROBE_MESSAGE.toByteArray(Charsets.US_ASCII)
                        sock.send(DatagramPacket(data, data.size, t, probePort))
                    }
                    round++
                }
                val p = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val ip = p.address?.hostAddress ?: continue
                if (found.containsKey(ip)) continue
                parseReply(String(p.data, p.offset, p.length, Charsets.UTF_8))?.let {
                    found[ip] = Found(host = ip, port = it.second, name = it.first)
                }
            }
        }
        return found.values.toList()
    }

    /** 255.255.255.255 + 每张 IPv4 网卡的子网广播地址（去重）。 */
    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        try {
            val nifs = NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in nifs) {
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (out.none { it.address.contentEquals(b.address) }) out.add(b)
                }
            }
        } catch (_: Exception) {
            // 无网络接口权限或枚举失败：至少还有全局广播地址。
        }
        return out
    }

    /** 应答 JSON → (显示名, TCP 端口)；版本不符或字段非法返回 null。 */
    private fun parseReply(text: String): Pair<String, Int>? = try {
        val o = JSONObject(text.trim())
        val port = o.optInt("port", -1)
        if (o.optInt("v", 0) == 1 && port in 1..65535) {
            Pair(o.optString("name", ""), port)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}
