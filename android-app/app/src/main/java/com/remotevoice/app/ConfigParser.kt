package com.remotevoice.app

/**
 * 当前 v3 配置串解析器：接受 rv://host[:port] 或 host[:port]。
 * 服务器身份由客户端内部 TOFU 管理，不进入用户配置。
 */
object ConfigParser {

    data class Parsed(
        val host: String,
        val port: Int,
    )

    private const val DEFAULT_PORT = 9432
    fun parse(raw: String): Parsed? {
        var value = raw.trim()
        if (value.startsWith("rv://", ignoreCase = true)) {
            value = value.substring(5)
        }
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null

        val queryIndex = value.indexOf('?')
        val authority = if (queryIndex < 0) value else value.substring(0, queryIndex)
        if (queryIndex >= 0) return null
        val endpoint = parseAuthority(authority) ?: return null
        return Parsed(endpoint.first, endpoint.second)
    }

    /** 将解析结果格式化为不会歧义的服务器地址，尤其是 IPv6。 */
    fun format(parsed: Parsed): String =
        if (parsed.host.contains(':')) "[${parsed.host}]:${parsed.port}"
        else "${parsed.host}:${parsed.port}"

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
