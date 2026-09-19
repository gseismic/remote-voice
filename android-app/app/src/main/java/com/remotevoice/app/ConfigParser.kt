package com.remotevoice.app

/**
 * 当前 v3 配置串解析器：接受 rv://host[:port] 或 host[:port]。
 * 服务器身份由客户端内部 TOFU 管理，不进入用户配置。
 */
object ConfigParser {

    /**
     * @param strict true=rvs://（标准 CA+主机名验证，无 TOFU）；false=rv://（TOFU）
     */
    data class Parsed(
        val host: String,
        val port: Int,
        val strict: Boolean = false,
    )

    /** 配对码解析结果（扫码配对，设计 docs/design/qr-pairing-20260919-overview.md §2）。 */
    data class Pairing(
        val host: String,
        val port: Int,
        val secret: String,
        val name: String,
        val strict: Boolean = false,
    )

    private const val DEFAULT_PORT = 9432

    fun parse(raw: String): Parsed? {
        var value = raw.trim()
        var strict = false
        if (value.startsWith("rvs://", ignoreCase = true)) {
            value = value.substring(6)
            strict = true
        } else if (value.startsWith("rv://", ignoreCase = true)) {
            value = value.substring(5)
        }
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null

        val queryIndex = value.indexOf('?')
        val authority = if (queryIndex < 0) value else value.substring(0, queryIndex)
        if (queryIndex >= 0) return null
        val endpoint = parseAuthority(authority) ?: return null
        return Parsed(endpoint.first, endpoint.second, strict)
    }

    /**
     * 配对码解析：rv[s]://<host>[:<port>]?s=<密码>&n=<设备名>。
     * `s` 必填；`n` 选填（URL 解码，UTF-8）；未知 query 参数忽略。
     * 与 [parse] 分开：手填地址不允许带 query 的既有语义不变。
     */
    fun parsePairing(raw: String): Pairing? {
        var value = raw.trim()
        var strict = false
        if (value.startsWith("rvs://", ignoreCase = true)) {
            value = value.substring(6)
            strict = true
        } else if (value.startsWith("rv://", ignoreCase = true)) {
            value = value.substring(5)
        }
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null

        val queryIndex = value.indexOf('?')
        if (queryIndex < 0) return null
        val authority = value.substring(0, queryIndex)
        val query = value.substring(queryIndex + 1)

        var secret = ""
        var name = ""
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            // 值按 + 为空格的表单规则还原，再 URL 解码；解码失败视为无效配对码
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            val decoded = try {
                java.net.URLDecoder.decode(rawValue.replace("+", "%20"), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
            when (key) {
                "s" -> secret = decoded
                "n" -> name = decoded
                else -> {} // 未知参数忽略（向前兼容）
            }
        }
        if (secret.isEmpty()) return null

        val endpoint = parseAuthority(authority) ?: return null
        return Pairing(endpoint.first, endpoint.second, secret, name, strict)
    }

    /** 将解析结果格式化为不会歧义的服务器地址（rvs:// 需带前缀，否则会退化成 TOFU 模式）。 */
    fun format(parsed: Parsed): String {
        val authority =
            if (parsed.host.contains(':')) "[${parsed.host}]:${parsed.port}"
            else "${parsed.host}:${parsed.port}"
        return if (parsed.strict) "rvs://$authority" else authority
    }

    /** 解析域名、IPv4、带括号的 IPv6，以及省略端口的地址。 */
    private fun parseAuthority(authority: String): Pair<String, Int>? {
        if (authority.isEmpty()) return null
        val endpoint = if (authority.startsWith('[')) {
            val end = authority.indexOf(']')
            if (end <= 1) return null
            val host = authority.substring(1, end)
            val suffix = authority.substring(end + 1)
            val port = when {
                suffix.isEmpty() -> DEFAULT_PORT
                suffix.startsWith(":") -> parsePort(suffix.substring(1)) ?: return null
                else -> return null
            }
            host to port
        } else {
            when (authority.count { it == ':' }) {
                0 -> authority to DEFAULT_PORT
                1 -> {
                    val split = authority.lastIndexOf(':')
                    val host = authority.substring(0, split)
                    val port = parsePort(authority.substring(split + 1)) ?: return null
                    host to port
                }
                else -> authority to DEFAULT_PORT // 未加括号的 IPv6：端口使用默认值
            }
        }
        val host = endpoint.first
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' || it == '?' ||
                    it == '[' || it == ']' }) return null
        return endpoint
    }

    private fun parsePort(raw: String): Int? =
        raw.toIntOrNull()?.takeIf { it in 1..65535 }
}
