package com.remotevoice.app

/**
 * 一行配置串解析器（协议 v2）：rv://host[:port]?f=<指纹>
 * 手机已无需密码/秘密（秘密随设备条目保存），配置串仅含服务器与指纹；
 * 由 relay 启动横幅直接打印，用户在设置页粘贴导入，消除手打。
 */
object ConfigParser {

    data class Parsed(
        val host: String,
        val port: Int,
        val fingerprint: String,
    )

    private val pattern = Regex(
        """^rv://([^/:?\s]+)(?::(\d{1,5}))?(?:\?f=([0-9a-fA-F:\-\s]+))?$"""
    )
    private const val DEFAULT_PORT = 9432
    private const val FP_LEN = 64

    fun parse(raw: String): Parsed? {
        val m = pattern.find(raw.trim()) ?: return null
        val port = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        val fingerprint = normalizeFingerprint(m.groupValues[3])
        if (fingerprint.isNotEmpty() && fingerprint.length != FP_LEN) return null
        return Parsed(m.groupValues[1], port, fingerprint)
    }

    /** 指纹归一化：剔除冒号/空格/连字符并转小写。 */
    fun normalizeFingerprint(s: String): String =
        s.filter { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }.lowercase()
}
