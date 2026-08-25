package com.remotevoice.app

/**
 * 一行配置串解析器：rv://host[:port]?t=<连接密码>&f=<指纹>
 * 由 relay 启动横幅直接打印，用户在设置页粘贴导入，消除三处手打。
 */
object ConfigParser {

    data class Parsed(
        val host: String,
        val port: Int,
        val password: String,
        val fingerprint: String,
    )

    private val pattern = Regex(
        """^rv://([^/:?\s]+)(?::(\d{1,5}))?\?t=([^&\s]+)&f=([0-9a-fA-F:\-\s]+)$"""
    )
    private const val DEFAULT_PORT = 9432
    private const val FP_LEN = 64

    fun parse(raw: String): Parsed? {
        val m = pattern.find(raw.trim()) ?: return null
        val port = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        val password = m.groupValues[3]
        val fingerprint = normalizeFingerprint(m.groupValues[4])
        if (password.isEmpty() || fingerprint.length != FP_LEN) return null
        return Parsed(m.groupValues[1], port, password, fingerprint)
    }

    /** 指纹归一化：剔除冒号/空格/连字符并转小写。 */
    fun normalizeFingerprint(s: String): String =
        s.filter { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }.lowercase()
}
