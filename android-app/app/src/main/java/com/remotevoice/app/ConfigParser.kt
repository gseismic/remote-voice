package com.remotevoice.app

/**
 * 解析 HTTPS 和原生 TLS 中继地址。
 * 裸地址 = Auto 模式（PLAN-025）：连接时先标准 CA+主机名验证，仅证书验证失败才回落 TOFU；
 * 服务器日后换真证书自动升级，用户全程无感。
 */
object ConfigParser {

    /** TLS 信任策略（与设计 docs/design/connection-simplify-20260919-overview.md §2.1 对应）。 */
    enum class TlsMode {
        /** 裸地址：先标准验证，证书错误回落 TOFU。 */
        AUTO,
        /** rvs://：只做标准 CA+主机名验证，绝不回落。 */
        STRICT,
        /** rv://：只做 TOFU（旧配置兼容）。 */
        TOFU,
        /** https:// 或配对码 rvh://：标准 TLS 验证和 HTTP/1.1 升级。 */
        HTTPS,
    }

    data class Parsed(
        val host: String,
        val port: Int,
        val mode: TlsMode = TlsMode.AUTO,
    )

    /** 配对码解析结果（扫码配对，设计 docs/design/qr-pairing-20260919-overview.md §2）。 */
    data class Pairing(
        val server: Parsed,
        val secret: String,
        val name: String,
        val deviceId: String?,
    )

    private const val DEFAULT_PORT = 9432
    private const val HTTPS_PORT = 443
    private val deviceIdPattern = Regex("[A-Za-z0-9_.:-]{8,128}")

    fun parse(raw: String): Parsed? = parseEndpoint(raw, pairing = false)

    private fun parseEndpoint(raw: String, pairing: Boolean): Parsed? {
        var value = raw.trim()
        val mode = when (value.substringBefore("://", missingDelimiterValue = "").lowercase()) {
            "" -> TlsMode.AUTO
            "rvs" -> TlsMode.STRICT
            "rv" -> TlsMode.TOFU
            "https" -> if (pairing) return null else TlsMode.HTTPS
            "rvh" -> if (pairing) TlsMode.HTTPS else return null
            else -> return null
        }
        if (mode != TlsMode.AUTO) value = value.substringAfter("://")
        if (mode == TlsMode.HTTPS && !pairing) value = value.removeSuffix("/")

        val defaultPort = if (mode == TlsMode.HTTPS) HTTPS_PORT else DEFAULT_PORT
        val endpoint = parseAuthority(value, defaultPort) ?: return null
        return Parsed(endpoint.first, endpoint.second, mode)
    }

    /** 配对码在地址后携带密码 s、可选设备 ID d 和名称 n。 */
    fun parsePairing(raw: String): Pairing? {
        val value = raw.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null

        val queryIndex = value.indexOf('?')
        if (queryIndex < 0) return null
        val server = parseEndpoint(value.substring(0, queryIndex), pairing = true) ?: return null
        val query = value.substring(queryIndex + 1)

        var secret = ""
        var name = ""
        var deviceId: String? = null
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            // 值按 + 为空格的表单规则还原，再 URL 解码；解码失败视为无效配对码
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            val decoded = try {
                java.net.URLDecoder.decode(rawValue.replace("+", "%20"), Charsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                return null
            }
            when (key) {
                "s" -> secret = decoded
                "n" -> name = decoded
                "d" -> deviceId = decoded.takeIf(deviceIdPattern::matches) ?: return null
                else -> {} // 未知参数忽略（向前兼容）
            }
        }
        if (secret.isEmpty()) return null

        return Pairing(server, secret, name, deviceId)
    }

    /** 显示地址时保留其传输和信任策略，供设置回显与重连使用。 */
    fun format(parsed: Parsed): String {
        val host = formatHost(parsed.host)
        return when (parsed.mode) {
            TlsMode.HTTPS -> if (parsed.port == HTTPS_PORT) "https://$host" else "https://$host:${parsed.port}"
            TlsMode.STRICT -> "rvs://$host:${parsed.port}"
            TlsMode.TOFU -> "rv://$host:${parsed.port}"
            TlsMode.AUTO -> "$host:${parsed.port}"
        }
    }

    fun formatHost(host: String): String = if (host.contains(':')) "[$host]" else host

    /** 解析域名、IPv4、带括号的 IPv6，以及省略端口的地址。 */
    private fun parseAuthority(authority: String, defaultPort: Int): Pair<String, Int>? {
        if (authority.isEmpty()) return null
        val endpoint = if (authority.startsWith('[')) {
            val end = authority.indexOf(']')
            if (end <= 1) return null
            val host = authority.substring(1, end)
            val suffix = authority.substring(end + 1)
            val port = when {
                suffix.isEmpty() -> defaultPort
                suffix.startsWith(":") -> parsePort(suffix.substring(1)) ?: return null
                else -> return null
            }
            host to port
        } else {
            when (authority.count { it == ':' }) {
                0 -> authority to defaultPort
                1 -> {
                    val split = authority.lastIndexOf(':')
                    val host = authority.substring(0, split)
                    val port = parsePort(authority.substring(split + 1)) ?: return null
                    host to port
                }
                else -> authority to defaultPort // 未加括号的 IPv6 使用默认端口
            }
        }
        val host = endpoint.first
        if (host.isEmpty() || host.any { it.isWhitespace() || it in "/?#@\\[]" }) return null
        return endpoint
    }

    private fun parsePort(raw: String): Int? =
        raw.toIntOrNull()?.takeIf { it in 1..65535 }
}
