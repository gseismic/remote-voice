package com.remotevoice.app

/**
 * 一行配置串解析器（协议 v3）：rv://host[:port]
 * 旧版本的 ?f=<指纹> 仍可解析，结果只供内部 TOFU 迁移，不是用户必填项。
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
        val rawFingerprint = m.groupValues[3]
        val fingerprint = normalizeFingerprint(rawFingerprint)
        if (rawFingerprint.isNotBlank() && fingerprint.isEmpty()) return null
        if (fingerprint.isNotEmpty() && fingerprint.length != FP_LEN) return null
        return Parsed(m.groupValues[1], port, fingerprint)
    }

    /** 指纹归一化：剔除冒号/空格/连字符并转小写。 */
    fun normalizeFingerprint(s: String): String =
        s.filter { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }.lowercase()
}
